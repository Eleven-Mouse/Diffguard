package com.diffguard.platform.messaging;

import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.review.model.DiffFileEntry;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewTaskPublisher")
class ReviewTaskPublisherTest {

    @Mock
    private RabbitMQConfig mqConfig;

    @Mock
    private Channel channel;

    @BeforeEach
    void setUp() {
        when(mqConfig.getChannel()).thenReturn(channel);
    }

    @Test
    @DisplayName("publish should send message to expected exchange and routing key")
    void publishShouldSendMessage() throws Exception {
        ReviewTaskMessage message = createMessage("task-1", "PIPELINE", false);
        ReviewTaskPublisher publisher = new ReviewTaskPublisher(mqConfig);
        try {
            String taskId = publisher.publish(message);
            assertEquals("task-1", taskId);

            ArgumentCaptor<AMQP.BasicProperties> propsCaptor = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
            ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);

            verify(channel, times(1)).basicPublish(
                    eq(RabbitMQConfig.REVIEW_EXCHANGE),
                    eq("review.pipeline.task"),
                    propsCaptor.capture(),
                    bodyCaptor.capture()
            );

            AMQP.BasicProperties props = propsCaptor.getValue();
            assertEquals("task-1", props.getMessageId());
            assertEquals(5, props.getPriority());
            assertEquals("application/json", props.getContentType());
            assertEquals(2, props.getDeliveryMode());
            assertNotNull(props.getTimestamp());
            assertNotNull(bodyCaptor.getValue());
        } finally {
            publisher.close();
        }
    }

    @Test
    @DisplayName("publishAsync should complete with task id")
    void publishAsyncShouldCompleteWithTaskId() throws Exception {
        ReviewTaskMessage message = createMessage("task-2", "MULTI_AGENT", true);
        ReviewTaskPublisher publisher = new ReviewTaskPublisher(mqConfig);
        try {
            CompletableFuture<String> future = publisher.publishAsync(message);
            assertEquals("task-2", future.join());
            verify(channel, times(1)).basicPublish(
                    eq(RabbitMQConfig.REVIEW_EXCHANGE),
                    eq("review.multi_agent.task"),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(byte[].class)
            );
        } finally {
            publisher.close();
        }
    }

    @Test
    @DisplayName("publish should throw IOException when channel publish fails")
    void publishShouldThrowWhenChannelFails() throws Exception {
        ReviewTaskMessage message = createMessage("task-3", "PIPELINE", false);
        doThrow(new java.io.IOException("mq down"))
                .when(channel).basicPublish(
                        eq(RabbitMQConfig.REVIEW_EXCHANGE),
                        eq("review.pipeline.task"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(byte[].class)
                );
        ReviewTaskPublisher publisher = new ReviewTaskPublisher(mqConfig);
        try {
            assertThrows(java.io.IOException.class, () -> publisher.publish(message));
        } finally {
            publisher.close();
        }
    }

    @Test
    @DisplayName("publishAsync should wrap IOException into RuntimeException")
    void publishAsyncShouldWrapIOException() throws Exception {
        ReviewTaskMessage message = createMessage("task-4", "PIPELINE", false);
        doThrow(new java.io.IOException("mq down"))
                .when(channel).basicPublish(
                        eq(RabbitMQConfig.REVIEW_EXCHANGE),
                        eq("review.pipeline.task"),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(byte[].class)
                );
        ReviewTaskPublisher publisher = new ReviewTaskPublisher(mqConfig);
        try {
            CompletableFuture<String> future = publisher.publishAsync(message);
            CompletionException ex = assertThrows(CompletionException.class, future::join);
            assertNotNull(ex.getCause());
            assertEquals("Failed to publish review task", ex.getCause().getMessage());
        } finally {
            publisher.close();
        }
    }

    private static ReviewTaskMessage createMessage(String taskId, String mode, boolean hotfix) {
        ReviewConfig config = new ReviewConfig();
        List<DiffFileEntry> entries = List.of(
                new DiffFileEntry("src/A.java", "@@ -0,0 +1 @@\n+class A {}", 12)
        );
        return new ReviewTaskMessage(
                taskId,
                mode,
                config,
                entries,
                "/tmp/project",
                "http://localhost:9090",
                List.of("src/A.java"),
                hotfix
        );
    }
}
