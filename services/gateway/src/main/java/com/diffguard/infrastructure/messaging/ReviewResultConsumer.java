package com.diffguard.infrastructure.messaging;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import com.diffguard.infrastructure.common.JacksonMapper;
import com.diffguard.infrastructure.persistence.ReviewResultRepository;
import com.diffguard.infrastructure.persistence.ReviewTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 消费 Python Agent 返回的 Review 结果。
 * RabbitMQ result.queue → Java → 持久化 MySQL → 通知等待的请求。
 */
public class ReviewResultConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewResultConsumer.class);
    private static final long PENDING_TTL_MINUTES = 10;
    private static final int MAX_PENDING = 1000;

    private final Channel channel;
    private final ReviewTaskRepository taskRepo;
    private final ReviewResultRepository resultRepo;
    private final ScheduledExecutorService cleanupScheduler;

    /** 等待结果的 pending tasks: taskId → CompletableFuture */
    private final Map<String, CompletableFuture<ReviewResult>> pendingTasks
            = new ConcurrentHashMap<>();

    private String consumerTag;
    private volatile boolean running = false;

    public ReviewResultConsumer(RabbitMQConfig mqConfig,
                                ReviewTaskRepository taskRepo,
                                ReviewResultRepository resultRepo) {
        this.channel = mqConfig.getChannel();
        this.taskRepo = taskRepo;
        this.resultRepo = resultRepo;
        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
        this.cleanupScheduler.scheduleAtFixedRate(this::cleanupPending, 2, 2, TimeUnit.MINUTES);
    }

    /**
     * 注册一个等待结果的 taskId。
     */
    public void registerPending(String taskId,
                                CompletableFuture<ReviewResult> future) {
        if (pendingTasks.size() >= MAX_PENDING) {
            throw new IllegalStateException("Too many pending tasks");
        }
        CompletableFuture<ReviewResult> timeoutFuture = future
                .orTimeout(PENDING_TTL_MINUTES, TimeUnit.MINUTES)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        pendingTasks.remove(taskId, future);
                    }
                });
        pendingTasks.put(taskId, timeoutFuture);
    }

    /**
     * 启动消费，阻塞当前线程。
     */
    public void startConsuming() throws IOException {
        running = true;
        channel.basicQos(10);  // prefetch 10 messages

        consumerTag = channel.basicConsume(RabbitMQConfig.RESULT_QUEUE, false,
                this::handleDelivery, this::handleCancel);
        log.info("ReviewResultConsumer started, consuming from {}", RabbitMQConfig.RESULT_QUEUE);
    }

    private void handleDelivery(String consumerTag, Delivery delivery) throws IOException {
        long deliveryTag = delivery.getEnvelope().getDeliveryTag();
        String taskId = ReviewTaskMessage.extractTaskId(delivery.getBody());

        try {
            ReviewResult result = parseResult(delivery.getBody());

            // 1. Complete pending future
            CompletableFuture<ReviewResult> pending = pendingTasks.remove(taskId);
            if (pending != null) {
                pending.complete(result);
            }

            // 2. Persist to MySQL
            if (resultRepo != null) {
                resultRepo.saveResult(taskId, result);
            }
            if (taskRepo != null) {
                taskRepo.updateStatus(taskId, "COMPLETED");
            }

            // 3. Ack
            channel.basicAck(deliveryTag, false);
            log.info("Consumed review result: taskId={}, issues={}", taskId, result.getIssues().size());

        } catch (Exception e) {
            log.error("Failed to process result for taskId={}: {}", taskId, e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            // Reject → goes to DLQ for retry
        }
    }

    private void handleCancel(String consumerTag) {
        log.warn("Consumer cancelled by broker: {}", consumerTag);
        running = false;
    }

    /**
     * 定期清理已完成或已取消的 pending futures，防止 map 无限增长。
     */
    private void cleanupPending() {
        pendingTasks.entrySet().removeIf(entry -> {
            CompletableFuture<?> f = entry.getValue();
            return f.isDone() || f.isCancelled();
        });
        log.debug("Pending tasks after cleanup: {}", pendingTasks.size());
    }

    private ReviewResult parseResult(byte[] body) {
        try {
            JsonNode root = JacksonMapper.MAPPER.readTree(body);
            String status = root.path("status").asText("failed");
            if ("failed".equals(status)) {
                throw new RuntimeException("Agent returned failed: " + root.path("error").asText("unknown"));
            }

            ReviewResult result = new ReviewResult();
            JsonNode issues = root.path("issues");
            if (issues.isArray()) {
                for (JsonNode issueNode : issues) {
                    ReviewIssue issue = new ReviewIssue();
                    issue.setSeverity(Severity.fromString(
                            issueNode.path("severity").asText("INFO")));
                    issue.setFile(issueNode.path("file").asText(""));
                    issue.setLine(issueNode.path("line").asInt(0));
                    issue.setType(issueNode.path("type").asText(""));
                    issue.setMessage(issueNode.path("message").asText(""));
                    issue.setSuggestion(issueNode.path("suggestion").asText(""));
                    result.addIssue(issue);
                }
            }

            result.setHasCriticalFlag(root.path("has_critical_flag").asBoolean(false));
            result.setTotalTokensUsed(root.path("total_tokens_used").asInt(0));
            return result;

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse review result", e);
        }
    }

    public boolean isRunning() { return running; }

    /**
     * 启动死信队列消费者，记录失败消息。
     */
    public void startDlqConsumer() throws IOException {
        channel.basicConsume(RabbitMQConfig.DEAD_LETTER_QUEUE, false,
                (consumerTag, delivery) -> {
                    long tag = delivery.getEnvelope().getDeliveryTag();
                    String body = new String(delivery.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                    log.error("DLQ message received: routingKey={}, body={}",
                            delivery.getEnvelope().getRoutingKey(),
                            body.length() > 500 ? body.substring(0, 500) + "..." : body);
                    channel.basicAck(tag, false);
                },
                consumerTag1 -> log.warn("DLQ consumer cancelled: {}", consumerTag1));
        log.info("DLQ consumer started on {}", RabbitMQConfig.DEAD_LETTER_QUEUE);
    }

    @Override
    public void close() {
        running = false;
        // 1. 关闭定时清理调度器
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 2. 取消所有剩余的 pending futures
        for (Map.Entry<String, CompletableFuture<ReviewResult>> entry : pendingTasks.entrySet()) {
            entry.getValue().cancel(true);
        }
        pendingTasks.clear();
        // 3. 取消 RabbitMQ consumer
        try {
            if (consumerTag != null) channel.basicCancel(consumerTag);
        } catch (IOException e) {
            log.warn("Error cancelling consumer: {}", e.getMessage());
        }
    }
}
