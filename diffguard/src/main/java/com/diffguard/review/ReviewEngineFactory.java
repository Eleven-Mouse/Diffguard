package com.diffguard.review;

import com.diffguard.agent.pipeline.MultiStageReviewService;
import com.diffguard.agent.reviewagents.MultiAgentReviewOrchestrator;
import com.diffguard.config.ReviewConfig;
import com.diffguard.llm.provider.LangChain4jClaudeAdapter;
import com.diffguard.llm.provider.LangChain4jOpenAiAdapter;
import com.diffguard.llm.provider.TokenTracker;
import com.diffguard.agent.tools.FileAccessSandbox;
import com.diffguard.model.DiffFileEntry;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 审查引擎工厂。
 * <p>
 * 根据配置和 CLI 标志创建对应的 {@link ReviewEngine} 实例。
 */
public class ReviewEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(ReviewEngineFactory.class);

    public enum EngineType {
        SIMPLE,
        PIPELINE,
        MULTI_AGENT
    }

    /**
     * 根据配置和标志解析引擎类型。
     */
    public static EngineType resolveEngineType(ReviewConfig config, boolean pipelineFlag, boolean multiAgentFlag) {
        if (multiAgentFlag) return EngineType.MULTI_AGENT;
        if (pipelineFlag || config.getPipeline().isEnabled()) return EngineType.PIPELINE;
        return EngineType.SIMPLE;
    }

    /**
     * 创建审查引擎实例。
     *
     * @param type       引擎类型
     * @param config     审查配置
     * @param projectDir 项目目录
     * @param diffEntries 差异文件列表（用于构建沙箱）
     * @param noCache    是否禁用缓存
     * @return 审查引擎实例
     */
    public static ReviewEngine create(EngineType type, ReviewConfig config,
                                       Path projectDir, List<DiffFileEntry> diffEntries,
                                       boolean noCache) {
        return switch (type) {
            case SIMPLE -> new ReviewService(config, projectDir, noCache);
            case PIPELINE -> {
                ChatModel chatModel = createChatModel(config);
                FileAccessSandbox sandbox = createSandbox(projectDir, diffEntries);
                yield new MultiStageReviewService(chatModel, sandbox);
            }
            case MULTI_AGENT -> {
                ChatModel chatModel = createChatModel(config);
                yield new MultiAgentReviewOrchestrator(chatModel, projectDir, config);
            }
        };
    }

    private static ChatModel createChatModel(ReviewConfig config) {
        AtomicInteger totalTokens = new AtomicInteger(0);
        TokenTracker tracker = tokens -> {
            int total = totalTokens.addAndGet(tokens);
            log.debug("Token usage: +{} (total: {})", tokens, total);
        };
        String providerName = config.getLlm().getProvider().toLowerCase();
        if ("claude".equals(providerName)) {
            return new LangChain4jClaudeAdapter(config.getLlm(), tracker).getChatModel();
        } else {
            return new LangChain4jOpenAiAdapter(config.getLlm(), tracker).getChatModel();
        }
    }

    private static FileAccessSandbox createSandbox(Path projectDir, List<DiffFileEntry> diffEntries) {
        Set<String> filePaths = diffEntries.stream()
                .map(DiffFileEntry::getFilePath)
                .collect(Collectors.toSet());
        return new FileAccessSandbox(projectDir, filePaths);
    }
}
