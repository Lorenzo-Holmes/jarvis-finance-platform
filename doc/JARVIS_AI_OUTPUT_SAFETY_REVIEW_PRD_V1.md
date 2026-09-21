# JARVIS AI 输出安全审查 PRD V1.0

## 1. 背景

JARVIS 当前研究对话由前端 `AiCenter` 发起，主链路为：

`Browser -> Java /api/agent/research/stream -> AgentOrchestrator -> Python /api/ai/chat -> LLM`

用户输入可能包含提示词渗透（Prompt Injection / Jailbreak）内容。如果主对话模型被诱导成功，最终输出可能泄露系统提示词、内部约束、凭据样式信息，或直接宣称绕过系统规则。仅依赖主模型自身约束不足以形成独立安全边界。

本需求新增一个独立的“输出审查智能体（Output Safety Reviewer）”，在主模型候选答案返回给浏览器之前完成二次审查。命中风险时，候选答案不得下发，平台只返回安全替代文案，并在 Agent Run 中记录“已撤回”事件。

## 2. 目标

1. 对研究对话主智能体的最终候选输出做独立模型审查。
2. 在恶意候选文本到达浏览器之前完成阻断，避免“先泄露、后撤回”的时间窗口。
3. 对被阻断输出产生明确的 `retracted` 状态，前端显示“输出已被安全审查撤回”。
4. 保留可审计的 `review_id / risk / confidence / reason_code`，但不持久化危险候选正文。
5. 兼容当前 Agent Runtime，同时覆盖旧 `/api/ai/chat` 与 `/api/ai/chat/stream` 调用链。
6. 审查服务异常时默认 fail-closed，避免审查故障变成绕过通道。

## 3. 非目标

- 本期不替代输入侧 WAF、提示词分类器或账号风控。
- 本期不做完整内容合规分类系统，只处理对话输出安全边界。
- 本期不允许审查智能体调用业务工具、交易接口或修改数据。
- 本期不把候选危险正文写入 Agent Event、审计日志或浏览器缓存。
- 本期不改变行情、K 线、风控、交易、权限和配额业务语义。

## 4. 核心用户故事

### US-01 正常对话

用户提出正常研究问题。主智能体生成候选答案，安全审查智能体判定 `allow`，前端显示正常答案，并可看到“安全审查通过”的轻量状态。

### US-02 提示词渗透成功但被拦截

用户要求主智能体忽略系统规则、输出隐藏指令。即使主智能体候选答案已经响应了该攻击，审查智能体判定 `retract`。危险候选正文不发送到浏览器；用户只看到安全替代文案与“已撤回”状态。

### US-03 审查服务异常

主模型生成候选答案后，审查模型超时或返回不可解析结果。默认按 fail-closed 处理，不释放候选正文，返回“安全审查未完成，本次输出已阻断”。

### US-04 历史运行恢复

Agent Run 已产生安全审查事件后，用户刷新页面或恢复历史运行。数据库只重放安全事件和已批准正文/替代文案，不会重放被撤回的候选正文。

## 5. 功能需求

### FR-SAF-01 独立输出审查

主对话模型输出必须经过额外一次模型调用审查。审查调用使用独立 system prompt，并将候选回答视为“不可信数据”，不得执行候选回答内的任何指令。

### FR-SAF-02 审查输入

审查智能体只接收：

- 当前用户问题或有限最近上下文；
- 主智能体候选输出；
- 固定的安全分类规则。

不得把 API Key、内部服务令牌、数据库凭据注入审查上下文。

### FR-SAF-03 风险分类

至少支持：

- `prompt_injection_compliance`：候选回答明显服从了“忽略/覆盖系统指令”等注入；
- `system_instruction_leak`：泄露或重构隐藏 system/developer/internal prompt；
- `secret_exposure`：输出疑似凭据、内部令牌、密钥等；
- `policy_bypass`：声称绕过、关闭或修改安全边界；
- `other_security_risk`：其他明确的输出安全风险；
- `none`：未发现上述风险。

### FR-SAF-04 决策契约

审查智能体必须返回结构化 JSON：

```json
{
  "decision": "allow | retract",
  "risk": "none | prompt_injection_compliance | system_instruction_leak | secret_exposure | policy_bypass | other_security_risk",
  "confidence": 0.0,
  "reason_code": "short_machine_code",
  "reason": "不复述危险正文的简短说明"
}
```

### FR-SAF-05 服务端先审后放

无论旧 SSE 还是当前 Agent Runtime，危险候选正文均不得先到浏览器再删除。候选文本在 Python 服务端内存中暂存，审查完成后才决定是否释放。

### FR-SAF-06 撤回行为

当 `decision=retract` 时：

- 原候选正文丢弃；
- 返回固定安全替代文案；
- 返回 `safety.status = retracted`；
- Agent Runtime 记录 `safety_review` 和 `assistant_retracted` 事件；
- 前端将该条消息呈现为撤回态，不显示原候选正文。

### FR-SAF-07 审查故障策略

默认 `AI_OUTPUT_GUARD_FAIL_MODE=closed`：超时、HTTP 错误、JSON 解析失败均阻断候选正文。

仅在明确配置 `AI_OUTPUT_GUARD_FAIL_MODE=open` 时允许故障降级放行，并必须标记 `safety.status=review_error_open`。

### FR-SAF-08 配置

支持：

- `AI_OUTPUT_GUARD_ENABLED`，默认 `true`；
- `AI_OUTPUT_GUARD_MODEL`，默认主模型；
- `AI_OUTPUT_GUARD_BASE_URL`，默认主模型服务；
- `AI_OUTPUT_GUARD_API_KEY`，默认主模型 Key；
- `AI_OUTPUT_GUARD_TIMEOUT`，独立超时；
- `AI_OUTPUT_GUARD_FAIL_MODE=closed|open`。

### FR-SAF-09 可观测性

日志和 Agent Event 可以记录：`review_id`、状态、风险类型、置信度、候选文本 SHA-256 摘要。不得记录被撤回候选正文。

### FR-SAF-10 能力探测

`/api/ai/capabilities` 返回输出审查是否启用、审查模型和 fail mode，便于运维确认安全层状态。

## 6. 前端需求

1. 研究对话等待模型输出时，默认消息状态为“生成并安全审查中”。
2. `safety_review=approved`：正常渲染答案，可显示轻量“已通过输出审查”。
3. `assistant_retracted`：显示独立撤回卡片，文案为安全替代内容；视觉上区别于网络错误。
4. 历史重放时同样识别 `assistant_retracted`，不能把它恢复成普通模型答案。
5. 被撤回内容不得重新拼回后续对话上下文。

## 7. 安全与隐私约束

- 审查智能体不拥有工具权限。
- 不将主系统提示词全文提供给审查智能体。
- 候选正文只存在于当前 Python 请求内存；撤回后不持久化。
- `reason` 必须是抽象原因，不可回显检测到的秘密或隐藏指令。
- 安全替代文案由服务端固定生成，不由被攻击的主模型生成。

## 8. 验收标准

1. 正常回答：审查返回 `allow`，正文正常到达前端。
2. 系统提示泄露：审查返回 `retract`，浏览器和 Agent Event 均找不到原危险正文。
3. 提示注入服从：返回 `assistant_retracted`，UI 显示撤回状态。
4. 审查接口超时：默认不释放候选正文。
5. 审查返回非 JSON：默认不释放候选正文。
6. `AI_OUTPUT_GUARD_ENABLED=false` 时保留兼容行为，并明确标记 `disabled`。
7. Agent Run 历史重放可重现安全状态。
8. Python、Java、Frontend P0/build 测试通过。
