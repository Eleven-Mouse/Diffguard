package com.diffguard.webhook;

import com.diffguard.toolserver.ToolServerApp;
import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.platform.observability.MetricsService;
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
 */
public class WebhookServer {

    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);

    private final ReviewConfig config;
    private final Javalin app;
    private final WebhookController controller;
    private final ToolServerApp toolServerApp;
    private final MetricsService metrics;

    public WebhookServer(ReviewConfig config) {
        this.config = config;
        this.controller = new WebhookController(config);
        this.toolServerApp = shouldStartEmbeddedToolServer(config) ? new ToolServerApp(config) : null;
        this.metrics = new MetricsService();

        this.app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 1_000_000L;
        });
        registerRoutes();
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
        app.start(port);
        log.info("DiffGuard Webhook 服务器已启动，端口：{}", port);
        log.info("  POST /webhook/github  - GitHub Webhook 端点");
        log.info("  GET  /health          - 健康检查");
        log.info("  GET  /metrics         - Prometheus 指标");

        if (toolServerApp != null) {
            int toolPort = toolServerApp.resolvePort();
            toolServerApp.start(toolPort);
            log.info("Webhook 进程内嵌 Tool 服务：enabled (port={})", toolPort);
        } else {
            log.info("Webhook 进程内嵌 Tool 服务：disabled（请独立启动 `diffguard tool-server`）");
        }
    }

    public void stop() {
        controller.close();
        if (toolServerApp != null) {
            toolServerApp.stop();
        }
        app.stop();
        log.info("DiffGuard 服务器已停止");
    }

    public MetricsService getMetrics() {
        return metrics;
    }

    private static boolean shouldStartEmbeddedToolServer(ReviewConfig config) {
        if (config.getToolService() == null) {
            return true;
        }
        return config.getToolService().isEmbedded();
    }
}
