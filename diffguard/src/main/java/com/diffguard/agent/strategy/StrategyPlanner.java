package com.diffguard.agent.strategy;

import com.diffguard.agent.strategy.ReviewStrategy.AgentType;
import com.diffguard.model.DiffFileEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 审查策略规划器。
 * <p>
 * 分析 diff 内容，自动选择最优审查策略：
 * - 确定启用哪些专用 Agent
 * - 设置各 Agent 的权重
 * - 添加针对性的审查规则
 * <p>
 * 策略选择逻辑：
 * - Controller 变更 → 重点 API 兼容性 + 安全
 * - DAO 变更 → 重点 SQL 风险 + 性能
 * - Service 变更 → 重点业务逻辑 + 性能
 * - Config 变更 → 重点安全配置
 * - 安全敏感代码 → 安全 Agent 权重最高
 * - 数据库操作 → 安全 + 性能 Agent
 * - 并发代码 → 性能 Agent 权重最高
 */
public class StrategyPlanner {

    private static final Logger log = LoggerFactory.getLogger(StrategyPlanner.class);

    /**
     * 分析 diff 并生成审查策略。
     */
    public static ReviewStrategy plan(List<DiffFileEntry> diffEntries) {
        DiffProfile profile = DiffProfiler.profile(diffEntries);
        ReviewStrategy strategy = selectStrategy(profile);

        log.info("策略规划完成: {} (风险={}, 主要类别={}, 启用Agent={})",
                strategy.getName(),
                profile.getOverallRisk(),
                profile.getPrimaryCategory(),
                strategy.getEnabledAgents());

        return strategy;
    }

    /**
     * 获取 diff 画像（用于调试或日志）。
     */
    public static DiffProfile getProfile(List<DiffFileEntry> diffEntries) {
        return DiffProfiler.profile(diffEntries);
    }

    private static ReviewStrategy selectStrategy(DiffProfile profile) {
        ReviewStrategy.Builder builder = ReviewStrategy.builder()
                .name("auto-detected")
                .priority(profile.getOverallRisk().ordinal());

        // 默认全部启用
        builder.agentWeight(AgentType.SECURITY, 1.0);
        builder.agentWeight(AgentType.PERFORMANCE, 1.0);
        builder.agentWeight(AgentType.ARCHITECTURE, 1.0);

        DiffProfile.FileCategory primary = profile.getPrimaryCategory();

        switch (primary) {
            case CONTROLLER -> applyControllerStrategy(builder, profile);
            case DAO -> applyDaoStrategy(builder, profile);
            case SERVICE -> applyServiceStrategy(builder, profile);
            case CONFIG -> applyConfigStrategy(builder, profile);
            case MODEL -> applyModelStrategy(builder, profile);
            case TEST -> applyTestStrategy(builder, profile);
            default -> applyDefaultStrategy(builder, profile);
        }

        // 全局风险调整
        adjustForRisk(builder, profile);

        return builder.build();
    }

    private static void applyControllerStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("API 兼容性审查")
                .description("Controller 变更：重点检查 API 兼容性、参数验证、权限控制");

        builder.agentWeight(AgentType.SECURITY, 1.5);
        builder.agentWeight(AgentType.ARCHITECTURE, 1.3);

        builder.focusArea("API 接口兼容性：URL 路径、HTTP 方法、参数是否向后兼容");
        builder.focusArea("参数验证：@RequestBody/@PathVariable 是否有校验");
        builder.focusArea("权限控制：接口是否有正确的权限注解");
        builder.focusArea("异常处理：全局异常处理是否覆盖新增异常类型");

        if (profile.hasExternalApiCalls()) {
            builder.additionalRule("重点检查 API 契约变更，确认不影响现有客户端");
        }
    }

    private static void applyDaoStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("数据库安全审查")
                .description("DAO 变更：重点检查 SQL 安全、查询性能、事务管理");

        builder.agentWeight(AgentType.SECURITY, 2.0);
        builder.agentWeight(AgentType.PERFORMANCE, 1.5);
        builder.agentWeight(AgentType.ARCHITECTURE, 0.5);

        builder.focusArea("SQL 注入风险：是否使用参数化查询");
        builder.focusArea("查询性能：是否有全表扫描、缺少索引的查询");
        builder.focusArea("事务管理：@Transactional 配置是否正确");
        builder.focusArea("数据完整性：外键约束、唯一约束是否满足");

        if (profile.hasDatabaseOperations()) {
            builder.additionalRule("所有 SQL 语句必须使用参数化查询，禁止字符串拼接");
            builder.additionalRule("检查是否有批量操作可能导致锁表");
        }
    }

    private static void applyServiceStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("业务逻辑审查")
                .description("Service 变更：重点检查业务逻辑正确性、并发安全、异常处理");

        builder.agentWeight(AgentType.SECURITY, 1.0);
        builder.agentWeight(AgentType.PERFORMANCE, 1.3);
        builder.agentWeight(AgentType.ARCHITECTURE, 1.0);

        builder.focusArea("业务逻辑正确性：边界条件、空值处理、异常分支");
        builder.focusArea("事务一致性：跨表操作是否在事务中");

        if (profile.hasConcurrencyCode()) {
            builder.agentWeight(AgentType.PERFORMANCE, 2.0);
            builder.focusArea("并发安全：共享状态是否有正确的同步机制");
            builder.additionalRule("检查是否有竞态条件和死锁风险");
        }

        if (profile.hasDatabaseOperations()) {
            builder.focusArea("N+1 查询：循环中是否有数据库调用");
        }
    }

    private static void applyConfigStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("安全配置审查")
                .description("配置变更：重点检查安全配置、密钥管理、环境差异");

        builder.agentWeight(AgentType.SECURITY, 2.5);
        builder.agentWeight(AgentType.PERFORMANCE, 0.3);
        builder.agentWeight(AgentType.ARCHITECTURE, 0.5);

        builder.focusArea("硬编码密钥：配置文件中是否有明文密码或 API Key");
        builder.focusArea("环境差异：开发/测试/生产配置是否一致");
        builder.focusArea("权限配置：是否有过于宽松的权限设置");
        builder.additionalRule("禁止在配置文件中硬编码密码、密钥等敏感信息");
    }

    private static void applyModelStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("数据模型审查")
                .description("Model 变更：重点检查序列化、向后兼容、验证注解");

        builder.agentWeight(AgentType.SECURITY, 0.8);
        builder.agentWeight(AgentType.PERFORMANCE, 0.5);
        builder.agentWeight(AgentType.ARCHITECTURE, 1.5);

        builder.focusArea("序列化兼容：新增/删除字段是否影响 JSON 序列化");
        builder.focusArea("验证注解：字段是否有正确的 @NotNull/@Size 等注解");
        builder.focusArea("向后兼容：字段变更是否影响现有 API 契约");
    }

    private static void applyTestStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("测试质量审查")
                .description("测试变更：降低审查强度，重点检查测试覆盖度");

        builder.agentWeight(AgentType.SECURITY, 0.3);
        builder.agentWeight(AgentType.PERFORMANCE, 0.3);
        builder.agentWeight(AgentType.ARCHITECTURE, 0.5);

        builder.focusArea("测试覆盖：变更是否被充分测试");
        builder.focusArea("测试质量：断言是否充分，边界条件是否覆盖");
    }

    private static void applyDefaultStrategy(ReviewStrategy.Builder builder, DiffProfile profile) {
        builder.name("通用代码审查")
                .description("通用审查：全面检查安全、性能和架构问题");

        builder.focusArea("代码质量：命名规范、复杂度、重复代码");
        builder.focusArea("错误处理：异常是否被正确处理");

        if (profile.hasDatabaseOperations()) {
            builder.agentWeight(AgentType.SECURITY, 1.5);
        }
        if (profile.hasConcurrencyCode()) {
            builder.agentWeight(AgentType.PERFORMANCE, 1.5);
        }
    }

    private static void adjustForRisk(ReviewStrategy.Builder builder, DiffProfile profile) {
        switch (profile.getOverallRisk()) {
            case CRITICAL -> {
                builder.additionalRule("高风险变更：所有 Agent 必须完成完整审查");
                builder.priority(3);
            }
            case HIGH -> {
                builder.additionalRule("中高风险变更：建议启用全部审查");
                builder.priority(2);
            }
            case MEDIUM -> builder.priority(1);
            case LOW -> {
                // 低风险可选择性减少审查
                builder.priority(0);
            }
        }
    }
}
