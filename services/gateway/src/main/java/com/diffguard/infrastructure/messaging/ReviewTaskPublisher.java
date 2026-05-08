package com.diffguard.infrastructure.messaging;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将 Review 任务发布到 RabbitMQ。
 * Java 调度中心 → RabbitMQ → Python Agent Consumer。
 */
public class ReviewTaskPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskPublisher.class);

    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mq-publish");
        t.setDaemon(true);
        return t;
    });

    private final Channel channel;

    public ReviewTaskPublisher(RabbitMQConfig mqConfig) {
        this.channel = mqConfig.getChannel();
    }

    /**
     * 异步发布 Review 任务到 RabbitMQ。
     *
     * @param message 任务消息
     * @return CompletableFuture<String> taskId
     */
    public CompletableFuture<String> publishAsync(ReviewTaskMessage message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return publish(message);
            } catch (IOException e) {
                throw new RuntimeException("Failed to publish review task", e);
            }
        }, publishExecutor);
    }

    /**
     * 同步发布 Review 任务到 RabbitMQ。
     */
    public String publish(ReviewTaskMessage message) throws IOException {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .messageId(message.getTaskId())
                .priority(message.getPriority())
                .contentType("application/json")
                .deliveryMode(2)  // persistent
                .timestamp(new java.util.Date(message.getCreatedAt()))
                .build();

        channel.basicPublish(
                RabbitMQConfig.REVIEW_EXCHANGE,
                message.getRoutingKey(),
                props,
                message.toJsonBytes()
        );

        log.info("Published review task: id={}, mode={}, routingKey={}, priority={}",
                message.getTaskId(), message.getMode(),
                message.getRoutingKey(), message.getPriority());
        return message.getTaskId();
    }

    @Override
    public void close() {
        publishExecutor.shutdown();
        // Channel lifecycle managed by RabbitMQConfig
    }
}
