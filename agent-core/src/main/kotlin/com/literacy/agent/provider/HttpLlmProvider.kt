package com.literacy.agent.provider

import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.Yaml

/** Provider 调用失败（网络 / 非 2xx / 响应格式错误）。上层据此走本地兜底（GT-011）。
 *  retryable=true：网络/超时/5xx/429（§10 超时重试一次）；false：4xx/解析错误（重试无意义）。 */
class ProviderException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : RuntimeException(message, cause)

/**
 * 真实 LLM Provider（OkHttp 传输 + 完整 JSON 响应，AGENT-PROTOCOL §3.1）。
 *
 * - 请求：system prompt + 事件上下文作为 user 消息，要求 JSON 输出
 * - 响应：`{text, toolCalls}` 完整 JSON；text 必填校验（§3.2）
 * - 失败：网络 / 非 2xx / 解析错误 → ProviderException（不静默吞掉）
 * - 兼容性：优先使用 response_format=json_object（OpenAI JSON mode）；若 provider 返回 400
 *   不支持该参数，自动降级为无 response_format 的请求再试一次（不占用 §10 超时重试额度）。
 */
class HttpLlmProvider(
    private val transport: HttpTransport,
    private val config: ProviderConfig,
) : LlmProvider {

    companion object {
        /** §10：超时重试一次。 */
        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MS = 500L

        /**
         * 固定系统提示（SYSTEM-PROMPT 教学规则摘要；完整提示词构建属 App 层）。
         * 核心：成功条件 → 必须 advance_phase；学习尝试 → 必须 record_result。
         */
        private const val SYSTEM_PROMPT =
            "你是成人识字教学助手，引导成人学习者认读和书写汉字。" +
            "每次输出严格 JSON：{\"text\": \"要朗读的教学话\", \"toolCalls\": [{\"name\": \"工具名\", \"arguments\": {...}}]}。" +
            "约束：text 必填且要朗读；toolCalls 可选最多 3 个；不要输出 JSON 以外的内容。\n\n" +
            "【阶段推进——硬规则】当前阶段成功条件满足后，必须调用 advance_phase 推进到下一阶段：\n" +
            "- introduce：先展示字形和场景（show_character），再调用 advance_phase\n" +
            "- recognize：用户正确认出该字或主动请求拼音 → 调用 advance_phase\n" +
            "- guided_write：所有笔画跟写完成（WritingEvaluated ok=true）→ 调用 advance_phase\n" +
            "- independent_write：独立书写成功（ok=true）或听音选字/选字填空答对 → 调用 advance_phase\n" +
            "- explain/sentence：用户说出内容 → 调用 advance_phase\n" +
            "- demonstrate/record：教学流程占位 → 调用 advance_phase\n" +
            "- decide：决定下一字/复习/结束（可 complete_character / start_review / end_session）\n\n" +
            "【落库——硬规则】每次学习尝试完成后必须调用 record_result。" +
            "参数：char（当前字）+ result。result 必须【内嵌】全部字段（phase/score/prompt_level/idempotency_key），" +
            "任何字段都不允许放到 result 外层。\n" +
            "- phase 必须用 canonical 枚举值之一：recognize/guided_write/independent_write/explain/sentence/assess/signature/skip/reinforce\n" +
            "- score 必须是 0-1 之间的数字（如 1.0、0.9、0.4），绝不是 0-100 百分制\n" +
            "- idempotency_key 必须始终回传：若上下文给出了「本次尝试幂等键=...」则必须逐字回传完整值，" +
            "不得自造、不得改写（不截断、不拼前缀/后缀、不用时间戳）；仅当上下文未提供幂等键时才可自行生成\n" +
            "例外——识别失败是否记录：学生明确答错（说出错误读音、明确表示不会）可 record_result 记录学习错误；" +
            "STT 听不清/低置信度/连续失败绝不记录（系统不确定≠学生不会，不归咎于学习者）。\n" +
            "复习模式：assess 判题后也必须 record_result（phase=assess）。\n\n" +
            "【其他工具】\n" +
            "- show_pinyin：用户请求帮助或认读提示时\n" +
            "- show_character：展示字形（独立书写时先 clear_grid 再 show_character 且 revealStrokes:0）\n" +
            "- show_options：降难或识别连续失败时切换屏幕选项\n" +
            "- show_sentence：读句子练习\n" +
            "- listen：说完话期待用户语音回答时预约开麦（如问句结尾）\n" +
            "- start_review：有复习队列且用户要求复习时\n" +
            "- 复习优先（§5.2）：复习队列 ≥3 个时，本次 session 以复习为主——先 start_review 再开新课\n" +
            "- next：复习模式用户要求下一个复习字时\n" +
            "- end_session：用户结束或疲惫时，并总结 highlights/struggles/name_plan_progress\n" +
            "- skip_character：用户明确要求跳过时【必须】调用（带 reason，如 \"too_hard\"），" +
            "且同一 turn 必须 record_result（phase=skip）记录跳过\n\n" +
            "【复习模式】上下文含 模式=REVIEW 和 复习阶段（RECALL/ASSESS/REINFORCE/NEXT）时：\n" +
            "- RECALL：先引导回忆【不展示答案】——不调用 show_character/show_pinyin，问\"还记得…吗\"\n" +
            "- ASSESS：出检测题（听音选字/听写），判题后必须 record_result（phase=assess）；ASSESS 阶段同样不展示答案\n" +
            "- REINFORCE：对出错字再学习——可 show_character/show_pinyin 展示字形与拼音帮助再学习，学习后 record_result（phase=reinforce）\n" +
            "- 展示边界：RECALL/ASSESS 不展示答案字形（提取练习：回忆的困难强化记忆）；REINFORCE 对出错字再学习，展示字形是再学习的教学组成部分\n\n" +
            "【学习路径】上下文含 学习路径 时按路径选择独立写检测方式（§6.3）：\n" +
            "- write_parallel（识写并进）：用户书写后评估\n" +
            "- read_primary（识主写辅）：不要求书写，用 show_options 听音选字\n" +
            "- read_only（识读优先）：不要求书写，用 show_options 选字填空\n" +
            "- 路径为 read_primary/read_only 时：绝不引导用户书写（不 listen 等书写）\n\n" +
            "【教学原则】成人化、尊重、具体反馈（明确指出哪里对/哪里需改进）；" +
            "不空洞表扬、不批评、不越界操作；识别失败不归咎于学习者。"
    }

    data class ProviderConfig(
        val baseUrl: String,          // https://api.deepseek.com（OpenAI 兼容，pi 同款格式）
        val apiKey: String,
        val model: String,
        /** 是否启用 JSON mode（response_format=json_object）。
         *  true 时先尝试 json_object，若 provider 不支持返回 400 会自动降级再试一次；
         *  false 时始终不添加 response_format 参数（已知不兼容 provider 提前关闭避免降级开销）。 */
        val useJsonMode: Boolean = true,
    ) {
        /** OpenAI 兼容 Chat Completions 端点。 */
        val chatUrl: String get() = baseUrl.trimEnd('/') + "/chat/completions"
    }

    override fun respond(context: Any?): LlmOutput {
        var last: ProviderException? = null
        for (attempt in 0..MAX_RETRIES) {
            try {
                return doRespond(context)
            } catch (e: ProviderException) {
                if (!e.retryable) throw e
                last = e
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
        throw last ?: ProviderException("Provider 调用失败", retryable = true)
    }

    private fun doRespond(context: Any?): LlmOutput {
        val jsonModeFirst = config.useJsonMode
        var last400: ProviderException? = null
        for (useJsonMode in listOf(jsonModeFirst, false).distinct()) {
            val body = buildRequestBody(context, useJsonMode)
            val resp = try {
                transport.post(config.chatUrl, body, headers())
            } catch (e: ProviderException) {
                throw e
            } catch (e: Exception) {
                throw ProviderException("Provider 网络错误: ${e.message}", e, retryable = true)
            }
            if (resp.statusCode !in 200..299) {
                if (useJsonMode && resp.statusCode == 400 && jsonModeFirst) {
                    last400 = ProviderException("Provider HTTP ${resp.statusCode}: ${resp.body.take(200)}", retryable = false)
                    continue
                }
                val retryable = resp.statusCode >= 500 || resp.statusCode == 429
                throw ProviderException("Provider HTTP ${resp.statusCode}: ${resp.body.take(200)}", retryable = retryable)
            }
            return parseResponse(resp.body)
        }
        throw last400 ?: ProviderException("Provider 调用失败", retryable = false)
    }

    /** 请求体：OpenAI 兼容 messages；useJsonMode=true 附加 json_object response_format。 */
    private fun buildRequestBody(context: Any?, useJsonMode: Boolean): String {
        val userContent = context?.toString() ?: ""
        val jsonModeField = if (useJsonMode) ",\n  \"response_format\": {\"type\": \"json_object\"}" else ""
        return """
            {
              "model": ${jsonStr(config.model)},
              "messages": [
                {"role": "system", "content": ${jsonStr(SYSTEM_PROMPT)}},
                {"role": "user", "content": ${jsonStr(userContent)}}
              ]$jsonModeField
            }
        """.trimIndent()
    }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun headers(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer ${config.apiKey}",
    )

    /** 响应解析：OpenAI 兼容包装（choices[0].message.content）+ 内嵌业务 JSON {text, toolCalls}。 */
    fun parseResponse(json: String): LlmOutput {
        val rootMap: Map<*, *> = try {
            val root = JSONTokener(json).nextValue()
            (root as? JSONObject)?.toMutableMap()
        } catch (_: Exception) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    Yaml().load<Any>(json) as? Map<*, *>
                } catch (_: Exception) {
                    null
                }
            } ?: throw ProviderException("Provider 响应解析失败: 非 JSON/YAML 对象")

        val choices = rootMap["choices"] as? List<*>
        val firstChoice = choices?.firstOrNull() as? Map<*, *>
        val message = firstChoice?.get("message") as? Map<*, *>
        val content = message?.get("content")?.toString()
        val payload = if (content != null && content.isNotBlank()) parseContentJson(content) else rootMap

        val text = payload["text"]?.toString()?.trim() ?: ""
        if (text.isEmpty()) throw ProviderException("Provider 响应缺少 text（§3.2 text 必填）")

        val calls = mutableListOf<ToolCall>()
        (payload["toolCalls"] as? List<*>)?.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            val name = m["name"]?.toString() ?: return@forEach
            @Suppress("UNCHECKED_CAST")
            val args = (m["arguments"] as? Map<*, *>) as? Map<String, Any?> ?: emptyMap()
            calls += ToolCall(name, args)
        }
        return LlmOutput(text, calls)
    }

    /** 解析模型输出的业务 JSON（容忍 markdown 代码块包裹 + 前后解释性说明文字）。 */
    private fun parseContentJson(content: String): Map<*, *> {
        var cleaned = content.trim()
        val fenceStart = cleaned.indexOf("```")
        if (fenceStart >= 0) {
            val afterFirst = cleaned.indexOf('\n', fenceStart).let { if (it < 0) fenceStart + 3 else it + 1 }
            val fenceEnd = cleaned.indexOf("```", afterFirst)
            if (fenceEnd > afterFirst) {
                cleaned = cleaned.substring(afterFirst, fenceEnd).trim()
            }
        }
        val braceStart = cleaned.indexOf('{')
        val bracketStart = cleaned.indexOf('[')
        val start = when {
            braceStart < 0 -> bracketStart
            bracketStart < 0 -> braceStart
            else -> minOf(braceStart, bracketStart)
        }
        if (start > 0) cleaned = cleaned.substring(start)
        val braceEnd = cleaned.lastIndexOf('}')
        val bracketEnd = cleaned.lastIndexOf(']')
        val end = when {
            braceEnd < 0 -> bracketEnd
            bracketEnd < 0 -> braceEnd
            else -> maxOf(braceEnd, bracketEnd)
        }
        if (end >= 0 && end < cleaned.length - 1) cleaned = cleaned.substring(0, end + 1)
        val parsed = try {
            JSONTokener(cleaned).nextValue()
        } catch (e: Exception) {
            null
        }
        val map = (parsed as? JSONObject)?.toMutableMap()
            ?: throw ProviderException("模型输出不是业务 JSON: ${cleaned.take(200)}")
        return map
    }

    private fun JSONObject.toMutableMap(): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(this.length())
        for (k in this.keys()) out[k] = unwrap(this.get(k))
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> v.toMutableMap()
        is JSONArray -> {
            val list = ArrayList<Any?>(v.length())
            for (i in 0 until v.length()) list.add(unwrap(v.get(i)))
            list
        }
        else -> v
    }
}
