package com.diffguard.cli;

import com.diffguard.config.ConfigLoader;
import com.diffguard.config.ReviewConfig;
import com.diffguard.exception.ConfigException;
import com.diffguard.webhook.WebhookServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "server", description = "启动 Webhook 服务器接收 GitHub PR 事件")
public class ServerCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ServerCommand.class);

    @CommandLine.Option(names = {"--port"}, description = "监听端口（默认从配置读取或 8080）")
    Integer port;

    @CommandLine.Option(names = {"--config"}, description = "配置文件路径")
    Path configPath;

    @CommandLine.ParentCommand
    DiffGuardMain parent;

    @Override
    public void run() {
        // 1. 加载配置
        ReviewConfig config;
        try {
            config = configPath != null
                    ? ConfigLoader.loadFromFile(configPath)
                    : ConfigLoader.load(Path.of("").toAbsolutePath());
        } catch (ConfigException e) {
            System.err.println("配置加载失败：" + e.getMessage());
            parent.setExitCode(1);
            return;
        }

        // 2. 校验 webhook 配置
        if (config.getWebhook() == null) {
            System.err.println("错误：配置文件中缺少 webhook 段。请参阅文档配置 webhook 参数。");
            parent.setExitCode(1);
            return;
        }

        // 3. 确定端口
        int effectivePort = port != null ? port : config.getWebhook().getPort();

        // 4. 启动服务器
        WebhookServer server = new WebhookServer(config);

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭 Webhook 服务器...");
            server.stop();
        }));

        server.start(effectivePort);

        // 主线程阻塞，保持 JVM 存活
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        parent.setExitCode(0);
    }
}
