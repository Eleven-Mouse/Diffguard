package com.diffguard.toolserver;

import com.diffguard.platform.config.ReviewConfig;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 独立 Tool Server 应用。
 * 仅暴露 Agent 工具回调端点，支持作为独立进程部署。
 */
public class ToolServerApp {

    private static final Logger log = LoggerFactory.getLogger(ToolServerApp.class);

    private final ReviewConfig config;
    private final ToolServerController controller;
    private final Javalin app;

    public ToolServerApp(ReviewConfig config) {
        this.config = config;
        this.controller = new ToolServerController();
        this.app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.http.maxRequestSize = 10_000_000L;
        });
        controller.registerRoutes(app);
        app.get("/health", ctx -> ctx.result("OK"));
    }

    public int resolvePort() {
        String envPort = System.getenv("DIFFGUARD_TOOL_SERVER_PORT");
        if (envPort != null && !envPort.isBlank()) {
            return Integer.parseInt(envPort.trim());
        }
        if (config.getToolService() != null) {
            return config.getToolService().getPort();
        }
        if (config.getAgentService() != null) {
            return config.getAgentService().getToolServerPort();
        }
        return 9090;
    }

    public void start(int port) {
        app.start(port);
        log.info("DiffGuard Tool 服务已启动，端口：{}", port);
        log.info("  POST /api/v1/tools/*  - Agent 工具回调端点");
        log.info("  GET  /health          - 健康检查");
    }

    public void stop() {
        controller.close();
        app.stop();
        log.info("DiffGuard Tool 服务已停止");
    }
}

