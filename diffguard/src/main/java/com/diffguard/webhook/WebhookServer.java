package com.diffguard.webhook;

import com.diffguard.config.ReviewConfig;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin Webhook 服务器。
 * 提供 /webhook/github 端点接收 GitHub 事件，以及 /health 健康检查。
 */
public class WebhookServer {

    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);

    private final Javalin app;
    private final WebhookController controller;

    public WebhookServer(ReviewConfig config) {
        this.controller = new WebhookController(config);
        this.app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 1_000_000L; // 1MB，防止 OOM 攻击
        });
        registerRoutes();
    }

    private void registerRoutes() {
        app.post("/webhook/github", controller::handleWebhook);
        app.get("/health", ctx -> ctx.result("OK"));
    }

    public void start(int port) {
        app.start(port);
        log.info("DiffGuard Webhook 服务器已启动，端口：{}", port);
        log.info("  POST /webhook/github  - GitHub Webhook 端点");
        log.info("  GET  /health          - 健康检查");
    }

    public void stop() {
        controller.close();
        app.stop();
        log.info("DiffGuard Webhook 服务器已停止");
    }
}
