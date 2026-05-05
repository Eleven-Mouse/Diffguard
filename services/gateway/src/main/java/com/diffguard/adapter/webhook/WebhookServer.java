package com.diffguard.adapter.webhook;

import com.diffguard.adapter.toolserver.ToolServerController;
import com.diffguard.infrastructure.config.ReviewConfig;
import com.diffguard.infrastructure.observability.MetricsService;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin Webhook 服务器。
 * <p>
 * 端点:
 * - POST /webhook/github - GitHub Webhook
 * - GET  /health         - 健康检查
 * - GET  /metrics        - Prometheus 指标
 * <p>
 * Tool Server (独立端口 9090):
 * - POST /api/v1/tools/* - Agent 工具回调端点
 */
public class WebhookServer {

    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);

    private final ReviewConfig config;
    private final Javalin app;
    private final Javalin toolApp;
    private final WebhookController controller;
    private final ToolServerController toolServerController;
    private final MetricsService metrics;

    public WebhookServer(ReviewConfig config) {
        this.config = config;
        this.controller = new WebhookController(config);
        this.toolServerController = new ToolServerController();
        this.metrics = new MetricsService();

        this.app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 1_000_000L;
        });
        registerRoutes();

        this.toolApp = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 10_000_000L;
        });
        toolServerController.registerRoutes(toolApp);
    }

    private void registerRoutes() {
        app.post("/webhook/github", controller::handleWebhook);
        app.get("/health", ctx -> ctx.result("OK"));

        // Prometheus metrics endpoint
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain");
            ctx.result(metrics.scrape());
        });
    }

    public void start(int port) {
        int toolPort = 9090;
        if (config.getAgentService() != null) {
            toolPort = config.getAgentService().getToolServerPort();
        }

        app.start(port);
        log.info("DiffGuard Webhook 服务器已启动，端口：{}", port);
        log.info("  POST /webhook/github  - GitHub Webhook 端点");
        log.info("  GET  /health          - 健康检查");
        log.info("  GET  /metrics         - Prometheus 指标");

        toolApp.start(toolPort);
        log.info("DiffGuard Tool 服务器已启动，端口：{}", toolPort);
        log.info("  POST /api/v1/tools/*  - Agent 工具回调端点");
    }

    public void stop() {
        controller.close();
        toolApp.stop();
        app.stop();
        log.info("DiffGuard 服务器已停止");
    }

    public MetricsService getMetrics() {
        return metrics;
    }
}
