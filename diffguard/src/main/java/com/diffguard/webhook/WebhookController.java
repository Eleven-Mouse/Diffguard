package com.diffguard.webhook;

import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.WebhookException;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Webhook 请求处理器。
 * 验证签名、解析 payload、过滤事件、异步派发审查任务。
 */
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final SignatureVerifier signatureVerifier;
    private final ReviewOrchestrator orchestrator;

    public WebhookController(ReviewConfig config) {
        String secret = config.getWebhook().resolveSecret();
        this.signatureVerifier = new SignatureVerifier(secret);
        this.orchestrator = new ReviewOrchestrator(config);
    }

    public void handleWebhook(Context ctx) {
        String payload = ctx.body();
        String signature = ctx.header("X-Hub-Signature-256");
        String event = ctx.header("X-GitHub-Event");

        // 1. 验证签名
        if (!signatureVerifier.verify(payload, signature)) {
            log.warn("Webhook 签名校验失败");
            ctx.status(401).result("Invalid signature");
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
}
