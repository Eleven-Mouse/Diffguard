package com.diffguard.infrastructure.messaging;

import com.diffguard.domain.review.model.ReviewIssue;
import com.diffguard.domain.review.model.ReviewResult;
import com.diffguard.domain.review.model.Severity;
import com.diffguard.infrastructure.persistence.ReviewResultRepository;
import com.diffguard.infrastructure.persistence.ReviewTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 消费 Python Agent 返回的 Review 结果。
 * RabbitMQ result.queue → Java → 持久化 MySQL → 通知等待的请求。
 */
public class ReviewResultConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewResultConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Channel channel;
    private final ReviewTaskRepository taskRepo;
    private final ReviewResultRepository resultRepo;

    /** 等待结果的 pending tasks: taskId → CompletableFuture */
    private final Map<String, java.util.concurrent.CompletableFuture<ReviewResult>> pendingTasks
            = new ConcurrentHashMap<>();

    private String consumerTag;
    private volatile boolean running = false;

    public ReviewResultConsumer(RabbitMQConfig mqConfig,
                                ReviewTaskRepository taskRepo,
                                ReviewResultRepository resultRepo) {
        this.channel = mqConfig.getChannel();
        this.taskRepo = taskRepo;
        this.resultRepo = resultRepo;
    }

    /**
     * 注册一个等待结果的 taskId。
     */
    public void registerPending(String taskId,
                                java.util.concurrent.CompletableFuture<ReviewResult> future) {
        pendingTasks.put(taskId, future);
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
            java.util.concurrent.CompletableFuture<ReviewResult> pending = pendingTasks.remove(taskId);
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

    private ReviewResult parseResult(byte[] body) {
        try {
            JsonNode root = MAPPER.readTree(body);
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
                            root.path("severity").asText("INFO")));
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

    @Override
    public void close() {
        running = false;
        try {
            if (consumerTag != null) channel.basicCancel(consumerTag);
        } catch (IOException e) {
            log.warn("Error cancelling consumer: {}", e.getMessage());
        }
    }
}
