# DiffGuard 修改计划

## 已完成

### 修复 LLM 输出非 JSON 问题

**问题**: 使用第三方代理 (`api.hiyo.top`) 时，LLM 返回对话式文本而非结构化 JSON，
导致 DiffGuard 降级为原始文本模式，commit 阻断判定不准确。

**根因**: 代理可能注入额外系统提示，或模型未严格遵循 JSON 输出约束。

**修改内容**:

1. **`OpenAiProvider.java`** — 添加 JSON 强制输出 + 降级重试
   - 添加 `response_format: {"type": "json_object"}` 参数强制模型输出合法 JSON
   - 增加降级机制：代理返回 400 时自动去掉该参数重试
   - 将请求逻辑拆分为 `call()` + `doCall()` 两层

2. **`default-system.txt`** — 强化系统提示
   - 新增约束：禁止输出自然语言、禁止解释身份或能力
   - 输出要求第 8 条：禁止输出 JSON 对象之外的任何内容

3. **`default-user.txt`** — 强化用户提示
   - 明确要求回复必须以 `{` 开头、`}` 结尾的合法 JSON 对象

---

## 待办

### 建议优化（非紧急）

- [ ] ClaudeProvider 也应添加类似的 JSON 强制输出机制
- [ ] 考虑支持 `.diffguard/prompts/` 下的自定义 prompt 模板覆盖
- [ ] 添加 LLM 响应格式自动检测与报告（帮助用户诊断代理兼容性）
