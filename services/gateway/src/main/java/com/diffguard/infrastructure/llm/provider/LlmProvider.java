package com.diffguard.infrastructure.llm.provider;

import com.diffguard.exception.LlmApiException;

import java.io.IOException;

/**
 * LLM 供应商接口，定义统一的 API 调用抽象。
 * 每个供应商实现负责构建请求、发送调用、解析响应。
 */
public interface LlmProvider {

    /**
     * 调用 LLM API 并返回响应中的文本内容。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 响应的文本内容
     * @throws LlmApiException API 调用失败
     * @throws IOException      网络 IO 异常
     * @throws InterruptedException 线程被中断
     */
    String call(String systemPrompt, String userPrompt) throws LlmApiException, IOException, InterruptedException;
}
