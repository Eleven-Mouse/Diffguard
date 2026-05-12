package com.diffguard;

import com.diffguard.adapter.webhook.WebhookServer;
import com.diffguard.infrastructure.config.ConfigLoader;
import com.diffguard.infrastructure.config.ReviewConfig;

/**
 * DiffGuard entry point.
 * Loads config and starts the webhook + tool server.
 */
public class DiffGuard {

    public static void main(String[] args) {
        int port = 8080;
        String configPath = null;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                System.out.println("DiffGuard - AI Code Review Webhook Server");
                System.out.println();
                System.out.println("Usage: java -jar diffguard.jar [options]");
                System.out.println("  --port <port>     Webhook server port (default: 8080)");
                System.out.println("  --config <path>   Config file path");
                System.out.println("  --help            Show this help");
                System.exit(0);
            }
        }

        ReviewConfig config = ConfigLoader.load(configPath);
        WebhookServer server = new WebhookServer(config);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
        }));

        server.start(port);
    }
}
