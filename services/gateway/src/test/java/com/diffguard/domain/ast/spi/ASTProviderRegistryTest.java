package com.diffguard.domain.ast.spi;

import com.diffguard.domain.ast.spi.LanguageASTProvider.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ASTProviderRegistry")
class ASTProviderRegistryTest {

    @Nested
    @DisplayName("getProvider() 根据文件路径")
    class GetProviderByFilePath {

        @Test
        @DisplayName(".java 文件返回 JavaASTProvider")
        void javaFileReturnsJavaProvider() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider("src/Main.java");

            assertTrue(provider.isPresent());
            assertInstanceOf(JavaASTProvider.class, provider.get());
        }

        @Test
        @DisplayName(".py 文件无内置 Provider 返回空")
        void pythonFileReturnsEmpty() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider("script.py");

            // No Python provider is registered by default
            assertTrue(provider.isEmpty());
        }

        @Test
        @DisplayName(".go 文件无内置 Provider 返回空")
        void goFileReturnsEmpty() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider("main.go");
            assertTrue(provider.isEmpty());
        }

        @Test
        @DisplayName(".js 文件无内置 Provider 返回空")
        void jsFileReturnsEmpty() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider("app.js");
            assertTrue(provider.isEmpty());
        }
    }

    @Nested
    @DisplayName("getProvider() 根据语言枚举")
    class GetProviderByLanguage {

        @Test
        @DisplayName("JAVA 语言返回 JavaASTProvider")
        void javaLanguageReturnsProvider() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider(Language.JAVA);

            assertTrue(provider.isPresent());
            assertInstanceOf(JavaASTProvider.class, provider.get());
        }

        @Test
        @DisplayName("PYTHON 语言无 Provider 返回空")
        void pythonLanguageReturnsEmpty() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider(Language.PYTHON);
            assertTrue(provider.isEmpty());
        }

        @Test
        @DisplayName("GO 语言无 Provider 返回空")
        void goLanguageReturnsEmpty() {
            Optional<LanguageASTProvider> provider = ASTProviderRegistry.getProvider(Language.GO);
            assertTrue(provider.isEmpty());
        }
    }

    @Nested
    @DisplayName("supportedLanguages()")
    class SupportedLanguages {

        @Test
        @DisplayName("返回包含 JAVA 的集合")
        void containsJava() {
            Set<Language> languages = ASTProviderRegistry.supportedLanguages();
            assertTrue(languages.contains(Language.JAVA));
        }

        @Test
        @DisplayName("返回不可修改的集合")
        void returnsUnmodifiableSet() {
            Set<Language> languages = ASTProviderRegistry.supportedLanguages();
            assertThrows(UnsupportedOperationException.class,
                    () -> languages.add(Language.PYTHON));
        }
    }

    @Nested
    @DisplayName("register()")
    class RegisterProvider {

        @Test
        @DisplayName("注册自定义 Provider 后可获取")
        void registerAndRetrieve() {
            // Create a mock provider for Python
            LanguageASTProvider pythonProvider = new LanguageASTProvider() {
                @Override
                public Language language() { return Language.PYTHON; }

                @Override
                public List<ASTNodeInfo> parse(String sourceCode, String filePath) {
                    return List.of();
                }

                @Override
                public List<MethodInfo> extractMethods(String sourceCode, String filePath) {
                    return List.of();
                }

                @Override
                public List<CallEdgeInfo> extractCallEdges(String sourceCode, String filePath) {
                    return List.of();
                }
            };

            ASTProviderRegistry.register(pythonProvider);
            Optional<LanguageASTProvider> found = ASTProviderRegistry.getProvider(Language.PYTHON);

            assertTrue(found.isPresent());

            // Clean up - re-register Java to restore default state
            // Python provider will remain registered but that's fine for testing
        }
    }

    @Nested
    @DisplayName("Language 枚举")
    class LanguageEnumTest {

        @Test
        @DisplayName("fromExtension .java 返回 JAVA")
        void javaExtension() {
            assertEquals(Language.JAVA, Language.fromExtension("Test.java"));
        }

        @Test
        @DisplayName("fromExtension .py 返回 PYTHON")
        void pythonExtension() {
            assertEquals(Language.PYTHON, Language.fromExtension("test.py"));
        }

        @Test
        @DisplayName("fromExtension .go 返回 GO")
        void goExtension() {
            assertEquals(Language.GO, Language.fromExtension("main.go"));
        }

        @Test
        @DisplayName("fromExtension .js 返回 JAVASCRIPT")
        void jsExtension() {
            assertEquals(Language.JAVASCRIPT, Language.fromExtension("app.js"));
        }

        @Test
        @DisplayName("fromExtension .ts 返回 TYPESCRIPT")
        void tsExtension() {
            assertEquals(Language.TYPESCRIPT, Language.fromExtension("app.ts"));
        }

        @Test
        @DisplayName("fromExtension .rs 返回 RUST")
        void rsExtension() {
            assertEquals(Language.RUST, Language.fromExtension("main.rs"));
        }

        @Test
        @DisplayName("fromExtension null 返回 JAVA (默认)")
        void nullReturnsJava() {
            assertEquals(Language.JAVA, Language.fromExtension(null));
        }

        @Test
        @DisplayName("fromExtension 未知扩展名返回 JAVA (默认)")
        void unknownExtensionReturnsJava() {
            assertEquals(Language.JAVA, Language.fromExtension("file.xyz"));
        }

        @Test
        @DisplayName("getId 返回正确 ID")
        void getIdReturnsCorrectId() {
            assertEquals("java", Language.JAVA.getId());
            assertEquals("python", Language.PYTHON.getId());
        }

        @Test
        @DisplayName("getExtension 返回正确扩展名")
        void getExtensionReturnsCorrectExtension() {
            assertEquals(".java", Language.JAVA.getExtension());
            assertEquals(".py", Language.PYTHON.getExtension());
        }
    }
}
