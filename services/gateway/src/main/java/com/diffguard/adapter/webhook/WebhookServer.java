package com.diffguard.adapter.webhook;

import com.diffguard.adapter.toolserver.ToolServerController;
import com.diffguard.infrastructure.config.ReviewConfig;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin server with webhook receiver + tool server.
 *
 * Main server (port 8080):
 *   POST /webhook/github - GitHub Webhook
 *   GET  /health         - Health check
 *
 * Tool Server (port 9090):
 *   POST /api/v1/tools/* - AST tool endpoints for Python agent
 */
public class WebhookServer {

    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);

    private final ReviewConfig config;
    private final Javalin app;
    private final Javalin toolApp;
    private final WebhookController controller;
    private final ToolServerController toolServerController;

    public WebhookServer(ReviewConfig config) {
        this.config = config;
        this.controller = new WebhookController(config);
        this.toolServerController = new ToolServerController();

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
    }

    public void start(int port) {
        int toolPort = 9090;
        if (config.getAgentService() != null) {
            toolPort = config.getAgentService().getToolServerPort();
        }

        app.start(port);
        log.info("DiffGuard Webhook server started on port {}", port);
        log.info("  POST /webhook/github  - GitHub Webhook endpoint");
        log.info("  GET  /health          - Health check");

        toolApp.start(toolPort);
        log.info("DiffGuard Tool server started on port {}", toolPort);
        log.info("  POST /api/v1/tools/*  - AST tool endpoints");
    }

    public void stop() {
        controller.close();
        toolServerController.close();
        toolApp.stop();
        app.stop();
        log.info("DiffGuard servers stopped");
    }
}
