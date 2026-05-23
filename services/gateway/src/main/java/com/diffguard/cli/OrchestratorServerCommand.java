package com.diffguard.cli;

import com.diffguard.orchestrator.ReviewOrchestratorServer;
import com.diffguard.exception.ConfigException;
import com.diffguard.platform.config.ConfigLoader;
import com.diffguard.platform.config.ReviewConfig;
import com.diffguard.platform.output.TerminalUI;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "orchestrator-server", description = "启动独立 Review Orchestrator 服务")
public class OrchestratorServerCommand implements Runnable {

    @CommandLine.Option(names = {"--port"}, description = "监听端口（默认 8088）")
    Integer port;

    @CommandLine.Option(names = {"--config"}, description = "配置文件路径")
    Path configPath;

    @CommandLine.ParentCommand
    DiffGuardMain parent;

    @Override
    public void run() {
        ReviewConfig config;
        try {
            config = configPath != null
                    ? ConfigLoader.loadFromFile(configPath)
                    : ConfigLoader.load(Path.of("").toAbsolutePath());
        } catch (ConfigException e) {
            TerminalUI.error("Config load failed: " + e.getMessage());
            parent.setExitCode(1);
            return;
        }

        ReviewOrchestratorServer server = new ReviewOrchestratorServer(config);
        int effectivePort = port != null ? port : 8088;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            TerminalUI.println("\nShutting down orchestrator server...");
            server.stop();
        }));

        server.start(effectivePort);
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        parent.setExitCode(0);
    }
}

