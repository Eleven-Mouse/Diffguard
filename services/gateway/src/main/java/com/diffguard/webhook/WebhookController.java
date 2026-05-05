package com.diffguard.webhook;

import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.exception.WebhookException;
import com.diffguard.service.ReviewOrchestrator;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Webhook 请求处理器。
 * 频率限制、验证签名、解析 payload、过滤事件、异步派发审查任务。
 */
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    /** 默认频率限制：每 IP 每分钟最多 30 次请求 */
    static final int DEFAULT_RATE_LIMIT = 30;

    private final SignatureVerifier signatureVerifier;
    private final ReviewOrchestrator orchestrator;
    private final RateLimiter rateLimiter;

    public WebhookController(ReviewConfig config) {
        String secret = config.getWebhook().resolveSecret();
        this.signatureVerifier = new SignatureVerifier(secret);
        this.orchestrator = new ReviewOrchestrator(config);
        this.rateLimiter = new RateLimiter(DEFAULT_RATE_LIMIT, Duration.ofMinutes(1));
    }

    /**
     * 包内可见构造方法，用于测试注入。
     */
    WebhookController(SignatureVerifier signatureVerifier, ReviewOrchestrator orchestrator) {
        this.signatureVerifier = signatureVerifier;
        this.orchestrator = orchestrator;
        this.rateLimiter = new RateLimiter(DEFAULT_RATE_LIMIT, Duration.ofMinutes(1));
    }

    /**
     * 完整构造方法，支持自定义 RateLimiter（用于测试）。
     */
    WebhookController(SignatureVerifier signatureVerifier, ReviewOrchestrator orchestrator, RateLimiter rateLimiter) {
        this.signatureVerifier = signatureVerifier;
        this.orchestrator = orchestrator;
        this.rateLimiter = rateLimiter;
    }

    public void handleWebhook(Context ctx) {
        String payload = ctx.body();
        String signature = ctx.header("X-Hub-Signature-256");
        String event = ctx.header("X-GitHub-Event");

        // 1. 验证签名（先于频率限制，避免未认证请求消耗限速配额）
        if (!signatureVerifier.verify(payload, signature)) {
            log.warn("Webhook 签名校验失败");
            ctx.status(401).result("Invalid signature");
            return;
        }

        // 2. 频率限制（签名验证通过后才计限速）
        String clientIp = resolveClientIp(ctx);
        if (!rateLimiter.allowRequest(clientIp)) {
            log.warn("请求被频率限制：IP={}", clientIp);
            ctx.status(429).result("Too many requests");
            return;
        }

        // 2. ping 事件（GitHub Webhook 配置验证）
        if ("ping".equals(event)) {
            ctx.status(200).result("pong");
            return;
        }

        // 3. 仅处理 pull_request 事件
        if (!"pull_request".equals(event)) {
            ctx.status(200).result("Ignored event: " + event);
            return;
        }

        // 4. 解析 payload
        GitHubPayloadParser.ParsedPullRequest pr;
        try {
            pr = GitHubPayloadParser.parse(payload);
        } catch (WebhookException e) {
            log.error("解析 payload 失败：{}", e.getMessage());
            ctx.status(400).result("Invalid payload");
            return;
        }

        // 5. 过滤 action
        if (!pr.isRelevantAction()) {
            ctx.status(200).result("Ignored action: " + pr.getAction());
            return;
        }

        log.info("收到 PR 审查请求：{}/pull/{} ({})", pr.getRepoFullName(), pr.getPrNumber(), pr.getAction());

        // 6. 立即返回 200，异步处理审查
        ctx.status(200).result("Accepted");
        orchestrator.processAsync(pr);
    }

    /**
     * 关闭控制器及其内部的编排器线程池。
     */
    public void close() {
        orchestrator.close();
    }

    /**
     * 解析客户端 IP。
     * 使用 Javalin 的 ctx.ip()，由框架/反向代理层处理可信头解析。
     * 不自行解析 X-Forwarded-For 以避免 IP 伪造。
     */
    static String resolveClientIp(Context ctx) {
        return ctx.ip();
    }
}
