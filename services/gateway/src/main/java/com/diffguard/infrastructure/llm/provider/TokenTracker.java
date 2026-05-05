package com.diffguard.infrastructure.llm.provider;

/**
 * Token 使用量追踪回调接口。
 */
public interface TokenTracker {

    /**
     * 累加 token 使用量。
     *
     * @param tokens 本次 API 调用消耗的 token 数
     */
    void addTokens(int tokens);
}
