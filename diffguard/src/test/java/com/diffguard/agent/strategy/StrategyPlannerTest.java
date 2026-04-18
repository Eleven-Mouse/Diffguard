package com.diffguard.agent.strategy;

import com.diffguard.agent.strategy.ReviewStrategy.AgentType;
import com.diffguard.model.DiffFileEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrategyPlannerTest {

    // --- DiffProfiler ---

    @Nested
    @DisplayName("DiffProfiler 文件分类")
    class DiffProfilerTests {

        @Test
        @DisplayName("Controller 文件分类")
        void categorizeController() {
            assertEquals(DiffProfile.FileCategory.CONTROLLER,
                    DiffProfiler.categorizeFile("src/main/java/com/example/OrderController.java"));
        }

        @Test
        @DisplayName("DAO 文件分类")
        void categorizeDao() {
            assertEquals(DiffProfile.FileCategory.DAO,
                    DiffProfiler.categorizeFile("src/main/java/com/example/OrderDAO.java"));
            assertEquals(DiffProfile.FileCategory.DAO,
                    DiffProfiler.categorizeFile("src/main/java/com/example/UserRepository.java"));
        }

        @Test
        @DisplayName("Service 文件分类")
        void categorizeService() {
            assertEquals(DiffProfile.FileCategory.SERVICE,
                    DiffProfiler.categorizeFile("src/main/java/com/example/OrderService.java"));
            assertEquals(DiffProfile.FileCategory.SERVICE,
                    DiffProfiler.categorizeFile("src/main/java/com/example/PaymentHandler.java"));
        }

        @Test
        @DisplayName("Model 文件分类")
        void categorizeModel() {
            assertEquals(DiffProfile.FileCategory.MODEL,
                    DiffProfiler.categorizeFile("src/main/java/com/example/OrderDTO.java"));
            assertEquals(DiffProfile.FileCategory.MODEL,
                    DiffProfiler.categorizeFile("src/main/java/com/example/UserEntity.java"));
        }

        @Test
        @DisplayName("Config 文件分类")
        void categorizeConfig() {
            assertEquals(DiffProfile.FileCategory.CONFIG,
                    DiffProfiler.categorizeFile("src/main/resources/application.yml"));
            assertEquals(DiffProfile.FileCategory.CONFIG,
                    DiffProfiler.categorizeFile("src/main/java/com/example/SecurityConfig.java"));
        }

        @Test
        @DisplayName("Test 文件分类")
        void categorizeTest() {
            assertEquals(DiffProfile.FileCategory.TEST,
                    DiffProfiler.categorizeFile("src/test/java/com/example/ServiceTest.java"));
        }

        @Test
        @DisplayName("null 和其他文件")
        void categorizeOther() {
            assertEquals(DiffProfile.FileCategory.OTHER, DiffProfiler.categorizeFile(null));
            assertEquals(DiffProfile.FileCategory.OTHER,
                    DiffProfiler.categorizeFile("src/main/java/com/example/App.java"));
        }
    }

    // --- Diff Profile Generation ---

    @Nested
    @DisplayName("Diff Profile 生成")
    class ProfileGenerationTests {

        @Test
        @DisplayName("Controller 变更被识别")
        void controllerChangeProfiled() {
            DiffEntry controllerEntry = new DiffEntry("src/OrderController.java",
                    "@PostMapping public ResponseEntity create(@RequestBody Order order) { orderService.create(order); return ok(); }");

            DiffProfile profile = DiffProfiler.profile(List.of(controllerEntry.toEntry()));
            assertEquals(DiffProfile.FileCategory.CONTROLLER, profile.getPrimaryCategory());
            assertTrue(profile.hasExternalApiCalls());
        }

        @Test
        @DisplayName("DAO 变更检测到数据库操作")
        void daoChangeDetectsDatabaseOps() {
            DiffEntry daoEntry = new DiffEntry("src/OrderDAO.java",
                    "public void save() { String sql = \"INSERT INTO orders VALUES (?)\"; jdbcTemplate.update(sql); }");

            DiffProfile profile = DiffProfiler.profile(List.of(daoEntry.toEntry()));
            assertTrue(profile.hasDatabaseOperations());
            assertEquals(DiffProfile.FileCategory.DAO, profile.getPrimaryCategory());
        }

        @Test
        @DisplayName("安全敏感代码被检测")
        void securitySensitiveCodeDetected() {
            DiffEntry entry = new DiffEntry("src/AuthService.java",
                    "public void login(String password) { String token = encrypt(password); }");

            DiffProfile profile = DiffProfiler.profile(List.of(entry.toEntry()));
            assertTrue(profile.hasSecuritySensitiveCode());
        }

        @Test
        @DisplayName("并发代码被检测")
        void concurrencyCodeDetected() {
            DiffEntry entry = new DiffEntry("src/CacheManager.java",
                    "private ConcurrentHashMap<String, Object> cache; synchronized void refresh() {}");

            DiffProfile profile = DiffProfiler.profile(List.of(entry.toEntry()));
            assertTrue(profile.hasConcurrencyCode());
        }

        @Test
        @DisplayName("多文件混合变更")
        void mixedFilesProfiled() {
            List<DiffFileEntry> entries = List.of(
                    new DiffEntry("src/OrderController.java", "@PostMapping create()").toEntry(),
                    new DiffEntry("src/OrderService.java", "process()").toEntry(),
                    new DiffEntry("src/OrderDAO.java", "jdbcTemplate.update(sql)").toEntry()
            ).stream().map(e -> e).toList();

            DiffProfile profile = DiffProfiler.profile(entries);
            assertEquals(3, profile.getTotalFiles());
            assertTrue(profile.hasDatabaseOperations());
            assertTrue(profile.hasExternalApiCalls());
            assertTrue(profile.getOverallRisk().ordinal() >= DiffProfile.RiskLevel.MEDIUM.ordinal());
        }
    }

    // --- Strategy Selection ---

    @Nested
    @DisplayName("策略选择")
    class StrategySelectionTests {

        @Test
        @DisplayName("Controller 变更选择 API 兼容性审查")
        void controllerSelectsApiStrategy() {
            DiffEntry entry = new DiffEntry("src/OrderController.java", "@PostMapping create(@RequestBody Order)");
            ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));

            assertEquals("API 兼容性审查", strategy.getName());
            assertTrue(strategy.getAgentWeights().get(AgentType.SECURITY) > 1.0);
            assertTrue(strategy.getFocusAreas().stream()
                    .anyMatch(f -> f.contains("API")));
        }

        @Test
        @DisplayName("DAO 变更选择数据库安全审查")
        void daoSelectsDatabaseStrategy() {
            DiffEntry entry = new DiffEntry("src/OrderDAO.java", "jdbcTemplate.update(sql)");
            ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));

            assertEquals("数据库安全审查", strategy.getName());
            assertTrue(strategy.getAgentWeights().get(AgentType.SECURITY) > 1.5);
            assertTrue(strategy.getFocusAreas().stream()
                    .anyMatch(f -> f.contains("SQL")));
        }

        @Test
        @DisplayName("Config 变更选择安全配置审查")
        void configSelectsSecurityStrategy() {
            DiffEntry entry = new DiffEntry("src/SecurityConfig.java", "password = admin123");
            ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));

            assertEquals("安全配置审查", strategy.getName());
            assertTrue(strategy.getAgentWeights().get(AgentType.SECURITY) > 2.0);
        }

        @Test
        @DisplayName("Service 变更选择业务逻辑审查")
        void serviceSelectsLogicStrategy() {
            DiffEntry entry = new DiffEntry("src/OrderService.java", "validate() process()");
            ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));

            assertEquals("业务逻辑审查", strategy.getName());
        }

        @Test
        @DisplayName("所有策略都至少启用一个 Agent")
        void allStrategiesEnableAgents() {
            for (String file : List.of("Controller.java", "Service.java", "DAO.java",
                    "Config.java", "DTO.java", "Util.java", "Test.java")) {
                DiffEntry entry = new DiffEntry("src/" + file, "content");
                ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));
                assertFalse(strategy.getEnabledAgents().isEmpty(),
                        file + " should enable at least one agent");
            }
        }

        @Test
        @DisplayName("高风险变更增加额外规则")
        void highRiskAddsRules() {
            // Large change with DB + Security
            String bigDiff = "password decrypt SQL INSERT PreparedStatement ".repeat(10);
            DiffEntry entry = new DiffEntry("src/BigService.java", bigDiff, 200, 5000);
            ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));

            assertTrue(strategy.getPriority() >= 2);
            assertFalse(strategy.getAdditionalRules().isEmpty());
        }

        @Test
        @DisplayName("低风险变更保持简单策略")
        void lowRiskIsSimple() {
            DiffEntry entry = new DiffEntry("src/Helper.java", "minor change", 10, 50);
            ReviewStrategy strategy = StrategyPlanner.plan(List.of(entry.toEntry()));

            assertEquals(0, strategy.getPriority());
        }
    }

    // --- Helper ---

    private record DiffEntry(String path, String content, int lineCount, int tokenCount) {
        DiffEntry(String path, String content) {
            this(path, content, content.split("\n").length, content.length() / 4);
        }

        DiffFileEntry toEntry() {
            return new DiffFileEntry(path, content, tokenCount);
        }
    }
}
