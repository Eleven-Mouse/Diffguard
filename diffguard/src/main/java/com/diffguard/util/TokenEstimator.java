package com.diffguard.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token 估算工具类。
 * 使用 CL100K_BASE（GPT-4）编码作为基础，对 Claude 模型应用修正系数。
 */
public final class TokenEstimator {

    private static final Encoding ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    /**
     * Claude tokenizer 与 GPT-4 tokenizer 的差异修正系数。
     * Claude 实际 token 数约为 CL100K 计数的 1.10-1.20 倍，取保守值 1.15。
     */
    private static final double CLAUDE_CORRECTION_FACTOR = 1.15;

    private TokenEstimator() {}

    /**
     * 根据 provider 类型估算文本的 token 数量。
     * 对 Claude 模型应用修正系数以补偿 tokenizer 差异。
     *
     * @param text     待估算的文本
     * @param provider LLM 供应商（"claude" 或 "openai"）
     * @return 估算的 token 数量
     */
    public static int estimate(String text, String provider) {
        int baseCount = ENCODING.countTokens(text);
        if ("claude".equalsIgnoreCase(provider)) {
            return (int) Math.ceil(baseCount * CLAUDE_CORRECTION_FACTOR);
        }
        return baseCount;
    }

    /**
     * 不考虑供应商差异的基础 token 计数。
     */
    public static int count(String text) {
        return ENCODING.countTokens(text);
    }
}
