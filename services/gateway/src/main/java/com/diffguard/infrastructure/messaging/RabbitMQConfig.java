package com.diffguard.infrastructure.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 连接和拓扑管理。
 * 声明 review.exchange (Topic) 和所需的队列、绑定、死信。
 */
public class RabbitMQConfig implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    public static final String REVIEW_EXCHANGE = "review.exchange";
    public static final String AGENT_QUEUE = "review.agent.queue";
    public static final String PIPELINE_QUEUE = "review.pipeline.queue";
    public static final String SIMPLE_QUEUE = "review.simple.queue";
    public static final String RESULT_QUEUE = "review.result.queue";
    public static final String DEAD_LETTER_EXCHANGE = "review.dlx";
    public static final String DEAD_LETTER_QUEUE = "review.dlq";

    private final Connection connection;
    private final Channel channel;

    public RabbitMQConfig(String host, int port, String user, String password) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUsername(user);
        factory.setPassword(password);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        factory.setRequestedHeartbeat(30);

        this.connection = factory.newConnection("diffguard-java");
        this.channel = connection.createChannel();
        declareTopology();
        log.info("RabbitMQ connected: {}:{}", host, port);
    }

    private void declareTopology() throws IOException {
        // Dead letter exchange
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, "direct", true);
        channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, null);
        channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, "dead");

        // Main exchange
        channel.exchangeDeclare(REVIEW_EXCHANGE, "topic", true);

        // Queues with DLX, TTL, and priority support
        declareQueueWithDlx(AGENT_QUEUE, 10);
        declareQueueWithDlx(PIPELINE_QUEUE, 10);
        declareQueueWithDlx(SIMPLE_QUEUE, 5);
        declareQueueWithDlx(RESULT_QUEUE, 5);

        // Bindings: routing key pattern → queue
        channel.queueBind(AGENT_QUEUE, REVIEW_EXCHANGE, "review.agent.#");
        channel.queueBind(PIPELINE_QUEUE, REVIEW_EXCHANGE, "review.pipeline.#");
        channel.queueBind(SIMPLE_QUEUE, REVIEW_EXCHANGE, "review.simple.#");
        channel.queueBind(RESULT_QUEUE, REVIEW_EXCHANGE, "review.result.#");

        log.info("RabbitMQ topology declared");
    }

    private void declareQueueWithDlx(String queueName, int maxPriority) throws IOException {
        channel.queueDeclare(queueName, true, false, false,
                java.util.Map.of(
                        "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                        "x-dead-letter-routing-key", "dead",
                        "x-max-priority", maxPriority,
                        "x-message-ttl", 600000  // 10 min TTL
                ));
    }

    public Channel getChannel() {
        return channel;
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (connection != null && connection.isOpen()) connection.close();
            log.info("RabbitMQ connection closed");
        } catch (Exception e) {
            log.warn("Error closing RabbitMQ: {}", e.getMessage());
        }
    }

    /**
     * 从环境变量构建配置。
     */
    public static RabbitMQConfig fromEnv() throws IOException, TimeoutException {
        String host = env("RABBITMQ_HOST", "localhost");
        int port = Integer.parseInt(env("RABBITMQ_PORT", "5672"));
        String user = env("RABBITMQ_USER", "guest");
        String password = env("RABBITMQ_PASSWORD", "guest");
        return new RabbitMQConfig(host, port, user, password);
    }

    private static String env(String key, String defaultVal) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val.trim() : defaultVal;
    }
}
