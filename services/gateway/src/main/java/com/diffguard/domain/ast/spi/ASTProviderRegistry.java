package com.diffguard.domain.ast.spi;

import com.diffguard.domain.ast.spi.LanguageASTProvider.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语言 AST Provider 注册表。
 * <p>
 * 管理所有已注册的 {@link LanguageASTProvider}，根据文件扩展名自动选择 Provider。
 * 支持 SPI 自动发现和手动注册两种方式。
 */
public class ASTProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ASTProviderRegistry.class);

    private static final Map<Language, LanguageASTProvider> providers = new ConcurrentHashMap<>();

    static {
        // 内置注册 Java Provider
        register(new JavaASTProvider());

        // SPI 自动发现
        loadSPIProviders();
    }

    /**
     * 注册一个 Provider。
     */
    public static void register(LanguageASTProvider provider) {
        providers.put(provider.language(), provider);
        log.info("Registered AST provider: {}", provider.language());
    }

    /**
     * 根据文件路径获取对应的 Provider。
     */
    public static Optional<LanguageASTProvider> getProvider(String filePath) {
        Language lang = Language.fromExtension(filePath);
        return Optional.ofNullable(providers.get(lang));
    }

    /**
     * 根据语言获取 Provider。
     */
    public static Optional<LanguageASTProvider> getProvider(Language language) {
        return Optional.ofNullable(providers.get(language));
    }

    /**
     * 获取所有已注册的语言。
     */
    public static Set<Language> supportedLanguages() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    private static void loadSPIProviders() {
        try {
            ServiceLoader<LanguageASTProvider> loader =
                    ServiceLoader.load(LanguageASTProvider.class);
            for (LanguageASTProvider provider : loader) {
                register(provider);
            }
        } catch (Exception e) {
            log.debug("SPI provider loading failed: {}", e.getMessage());
        }
    }
}
