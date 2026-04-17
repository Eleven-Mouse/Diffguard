package com.diffguard.llm.provider;

import java.util.Set;

/**
 * LLM Provider 共享常量定义。
 * 集中管理模型分类、Prompt 强化指令等跨 Provider 复用的常量，
 * 避免在多个 Adapter 中重复维护。
 */
public final class LlmConstants {

    private LlmConstants() {}

    /** 不支持 temperature 参数的推理模型（GPT-5 系列） */
    public static final Set<String> NO_TEMPERATURE_MODELS = Set.of(
            "gpt-5", "gpt-5-codex", "gpt-5.1", "gpt-5.1-codex",
            "gpt-5.2", "gpt-5.2-codex", "gpt-5.3-codex", "gpt-5.4");

    /** 需要禁用扩展思考的模型 */
    public static final Set<String> THINKING_MODELS = Set.of(
            "o1", "o1-mini", "o3", "o3-mini", "o3-pro");

    /** 降级模式下追加到用户消息末尾的 JSON 强化指令 */
    public static final String JSON_ENFORCE_SUFFIX =
            "\n\n【绝对要求】你的回复必须且仅是一个合法的 JSON 对象，以 { 开头，以 } 结尾。"
            + "禁止输出任何 JSON 之外的内容，包括问候语、解释、能力描述或思考过程。";
}
