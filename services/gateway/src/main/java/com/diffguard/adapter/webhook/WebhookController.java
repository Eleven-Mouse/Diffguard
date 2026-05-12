package com.diffguard.adapter.webhook;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.WebhookException;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Webhook request handler.
 * Verifies signature, rate-limits, parses payload, forwards to Python agent.
 */
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    static final int DEFAULT_RATE_LIMIT = 30;

    private final SignatureVerifier signatureVerifier;
    private final WebhookDispatcher dispatcher;
    private final RateLimiter rateLimiter;

    public WebhookController(ReviewConfig config) {
        String secret = config.getWebhook() != null ? config.getWebhook().resolveSecret() : null;
        this.signatureVerifier = new SignatureVerifier(secret);
        this.dispatcher = new WebhookDispatcher(config);
        this.rateLimiter = new RateLimiter(DEFAULT_RATE_LIMIT, Duration.ofMinutes(1));
    }

    WebhookController(SignatureVerifier signatureVerifier, WebhookDispatcher dispatcher, RateLimiter rateLimiter) {
        this.signatureVerifier = signatureVerifier;
        this.dispatcher = dispatcher;
        this.rateLimiter = rateLimiter;
    }

    public void handleWebhook(Context ctx) {
        String payload = ctx.body();
        String signature = ctx.header("X-Hub-Signature-256");
        String event = ctx.header("X-GitHub-Event");

        // 1. Verify signature
        if (!signatureVerifier.verify(payload, signature)) {
            log.warn("Webhook signature verification failed");
            ctx.status(401).result("Invalid signature");
            return;
        }

        // 2. Rate limit
        String clientIp = resolveClientIp(ctx);
        if (!rateLimiter.allowRequest(clientIp)) {
            log.warn("Rate limited: IP={}", clientIp);
            ctx.status(429).result("Too many requests");
            return;
        }

        // 3. Ping event
        if ("ping".equals(event)) {
            ctx.status(200).result("pong");
            return;
        }

        // 4. Only handle pull_request events
        if (!"pull_request".equals(event)) {
            ctx.status(200).result("Ignored event: " + event);
            return;
        }

        // 5. Parse payload
        GitHubPayloadParser.ParsedPullRequest pr;
        try {
            pr = GitHubPayloadParser.parse(payload);
        } catch (WebhookException e) {
            log.error("Failed to parse payload: {}", e.getMessage());
            ctx.status(400).result("Invalid payload");
            return;
        }

        // 6. Filter action
        if (!pr.isRelevantAction()) {
            ctx.status(200).result("Ignored action: " + pr.getAction());
            return;
        }

        log.info("Received PR review request: {}/pull/{} ({})", pr.getRepoFullName(), pr.getPrNumber(), pr.getAction());

        // 7. Return 200 immediately, dispatch async
        ctx.status(200).result("Accepted");
        dispatcher.dispatchAsync(pr);
    }

    public void close() {
        dispatcher.close();
    }

    static String resolveClientIp(Context ctx) {
        return ctx.ip();
    }
}
