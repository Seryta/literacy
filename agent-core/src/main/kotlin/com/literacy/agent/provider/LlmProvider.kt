package com.literacy.agent.provider

import com.literacy.agent.model.LlmOutput

/** LLM Provider 抽象（RESEARCH-TECH：统一请求/响应结构，完整 JSON 优先）。 */
interface LlmProvider {
    /** 输入上下文（第一版透传调用序号，后续替换为结构化上下文块）。 */
    fun respond(context: Any?): LlmOutput
}

/**
 * 脚本化 mock provider：按序返回预置输出。
 * 超出脚本时返回空回复（text 必填，toolCalls 为空——协议 §3.2）。
 */
class ScriptedLlmProvider(private val script: List<LlmOutput>) : LlmProvider {
    private var index = 0
    override fun respond(context: Any?): LlmOutput =
        script.getOrElse(index++) { LlmOutput("好的", emptyList()) }
}
