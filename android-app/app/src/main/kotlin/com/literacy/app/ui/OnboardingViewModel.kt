package com.literacy.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.literacy.app.settings.AppSettings
import com.literacy.app.ui.voice.VoiceHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次引导（onboarding）状态机：活泼机器人引导新用户完成建档。
 *
 * 流程：欢迎 → 选宠物（说"第几个"或点击）→ 问姓名（语音/输入）→
 *       确认姓名（3 次未确认 → 输入兜底）→ 引导开始学习（说"开始"或点击）
 *
 * 全程本地驱动（不依赖 LLM/API key——onboarding 时 key 可能还没配），
 * 语音转写由 MainActivity 的 SpeechInputManager 提供，经 [handleVoice] 进入。
 */
class OnboardingViewModel(
    private val settings: AppSettings,
    private val store: com.literacy.agent.store.LearningStore,
) : ViewModel() {

    enum class Step { WELCOME, VOICE_PREP, PICK_MASCOT, ASK_NAME, CONFIRM_NAME, FALLBACK_INPUT, GUIDE_START, DONE }

    data class ObState(
        val step: Step = Step.WELCOME,
        val mascotIndex: Int = 0,
        val pendingName: String = "",
        val confirmAttempts: Int = 0,
        val robotText: String = "",
        val showOptions: List<String> = emptyList(),
        val showInput: Boolean = false,
        val inputLabel: String = "",
        val voiceDownloading: Boolean = false,      // 语音包下载中
        val voiceDownloadProgress: Int = 0,         // 0..100
        val voiceDownloadDone: Boolean = false,     // 本次引导内是否已下载完成
        val voiceFailCount: Int = 0,                // 语音包下载失败次数（>=2 出现弱化系统语音出口）
        val voiceModelsReady: Boolean = false,      // 模型是否已就绪
        val voiceError: String = "",                // 最近一次下载失败的具体原因（排查用）
    )

    var ui by mutableStateOf(ObState())
        private set

    /** 实时转写字幕（用户边说边显示，onPartialResults 更新）。 */
    var partialText by mutableStateOf("")
        private set

    fun onPartial(text: String) {
        partialText = text
    }

    /** 对话回合推进时清空旧字幕。 */
    private fun clearPartial() {
        partialText = ""
    }

    /** 建档完成回调（UI 层导航）。 */
    var onComplete: ((fullName: String, startNow: Boolean) -> Unit)? = null

    init {
        // 初始欢迎语（语音老师是使用前提，先准备再选宠物/姓名）
        ui = ui.copy(
            step = Step.WELCOME,
            robotText = "嗨！我是${Mascots.candidates[0].variant.label}，以后我陪你一起认字！先帮你把语音老师准备好，然后选一个小伙伴。",
            showOptions = listOf("开始"),
        )
    }

    // ── 步骤推进 ────────────────────────────────────────────────────
    /** 语音包准备（不可跳过）：就绪直接过，未就绪下载（失败重试，2 次后弱化系统语音出口）。 */
    private fun goVoicePrep() {
        clearPartial()
        val ready = VoiceHub.modelManager.ttsReady() && VoiceHub.modelManager.sttReady()
        ui = ui.copy(
            step = Step.VOICE_PREP,
            robotText = if (ready) "语音老师已经准备好了！接下来选一个你喜欢的小伙伴吧。" else "先把语音老师准备好：下载语音包后，女声朗读和语音识别完全离线、更清楚。约 210MB，建议连 Wi-Fi。",
            showOptions = emptyList(),
            showInput = false,
            voiceModelsReady = ready,
        )
        if (ready) goPickMascot()
    }

    private fun goPickMascot() {
        clearPartial()
        ui = ui.copy(
            step = Step.PICK_MASCOT,
            robotText = "你喜欢哪一个？说“第几个”，比如“第一个”；也可以直接点卡片。不喜欢挑的话，说“随便”就用小绿。",
            showOptions = emptyList(),
            showInput = false,
        )
    }

    private fun goAskName(reason: String) {
        clearPartial()
        ui = ui.copy(
            step = Step.ASK_NAME,
            robotText = reason,
            showOptions = emptyList(),
            showInput = true,
            inputLabel = "你的名字（也可以直接打字）",
        )
    }

    private fun goConfirmName() {
        clearPartial()
        ui = ui.copy(
            step = Step.CONFIRM_NAME,
            robotText = "我听到的是「${ui.pendingName}」，对吗？说“对”或“不对”。",
            showOptions = listOf("对", "不对"),
            showInput = false,
        )
    }

    private fun goFallbackInput() {
        clearPartial()
        ui = ui.copy(
            step = Step.FALLBACK_INPUT,
            robotText = "没关系，我们把名字打出来吧。点下面的框，输入你的名字，然后点“确定”。",
            showOptions = emptyList(),
            showInput = true,
            inputLabel = "请输入你的名字",
        )
    }

    private fun goGuideStart() {
        clearPartial()
        if (ui.pendingName.isEmpty()) {
            // 未录姓名：跳过引导学习，直接完成（首页建档卡引导）
            finishDirect()
            return
        }
        ui = ui.copy(
            step = Step.GUIDE_START,
            robotText = "都记住啦！以后我叫你「${ui.pendingName}」好不好？现在我们开始学你的名字，好吗？说“开始”，或者点下面的按钮。",
            showOptions = listOf("开始学习", "等一会"),
            showInput = false,
        )
    }

    // ── 用户输入入口 ────────────────────────────────────────────────
    fun handleVoice(text: String) {
        clearPartial()   // 回合结束收起实时字幕
        when (ui.step) {
            Step.WELCOME -> {
                if (isYes(text)) goVoicePrep()
                else ui = ui.copy(robotText = "点下面的“开始”按钮，或者直接说“开始”。")
            }
            Step.VOICE_PREP -> {
                // 语音包准备：不可跳过（下载/重试；失败 2 次后才允许"用系统"弱化出口）
                if (!ui.voiceDownloading) {
                    when {
                        text.contains("下载") || text.contains("好") || text.contains("要") || text.contains("重试") -> startVoiceDownload()
                        text.contains("系统") && ui.voiceFailCount >= 2 -> useSystemVoice()
                        text.contains("跳过") || text.contains("以后") || text.contains("不") ->
                            ui = ui.copy(robotText = "语音老师很重要，先下载好再开始（建议连 Wi-Fi）。")
                        else -> ui = ui.copy(robotText = "说“下载”就开始下载语音包。")
                    }
                }
            }
            Step.PICK_MASCOT -> {
                // 可跳过：说"随便/跳过/默认"用小绿
                val idx = pickMascotIndex(text)
                when {
                    idx != null -> onSelectMascot(idx)
                    text.contains("随便") || text.contains("跳过") || text.contains("默认") || text.contains("都可以") -> {
                        ui = ui.copy(mascotIndex = 0)
                        goAskName("好！那就用${Mascots.candidates[0].variant.label}。那……你叫什么名字呀？说给我听，或者点下面的框打出来。（说“跳过”也可以先不录）")
                    }
                    else -> ui = ui.copy(robotText = "没听清你说的是第几个，再说一次，比如“第一个”。不想挑就说“随便”。")
                }
            }
            Step.ASK_NAME -> {
                // 可跳过：说"跳过/以后/先不录" → 直接完成（首页建档卡引导）
                if (text.contains("跳过") || text.contains("以后") || text.contains("先不") || text.contains("不录") || text.contains("不用")) {
                    goGuideStart()   // pendingName 空 → finishDirect
                    return
                }
                val name = extractName(text)
                if (name.isNotEmpty()) {   // 单字名也接受（真名单字名用户不被拒）
                    ui = ui.copy(pendingName = name)
                    goConfirmName()
                } else {
                    ui = ui.copy(robotText = "没听清你的名字，能再说一次吗？或者点下面的框打出来。（说“跳过”也可以先不录）")
                }
            }
            Step.CONFIRM_NAME -> {
                when {
                    isNo(text) -> onConfirmName(false)
                    isYes(text) -> onConfirmName(true)
                    else -> ui = ui.copy(robotText = "你说的是“对”还是“不对”呀？或者点下面的按钮。")
                }
            }
            Step.FALLBACK_INPUT -> {
                val name = extractName(text)
                if (name.isNotEmpty()) { ui = ui.copy(pendingName = name); goGuideStart() }
            }
            Step.GUIDE_START -> {
                // 否定/延后词优先（"晚点再学"/"等一会再学"不能被"学"误判为开始）
                when {
                    text.contains("等") || text.contains("不") || text.contains("晚") || text.contains("以后") ||
                        text.contains("明天") || text.contains("回头") -> finish(startNow = false)
                    text.contains("开始") || text.contains("好") || text.contains("行") || text.contains("学") || text.contains("走吧") ->
                        finish(startNow = true)
                    else -> ui = ui.copy(robotText = "说“开始”我们就开始学你的名字；或者点“等一会”。")
                }
            }
            Step.VOICE_PREP -> { /* 已在上方分支处理 */ }
            Step.DONE -> {}
        }
    }

    fun onSelectMascot(index: Int) {
        if (index !in Mascots.candidates.indices) return
        ui = ui.copy(mascotIndex = index)
        goAskName("好嘞！你喜欢${Mascots.candidates[index].variant.label}！那……你叫什么名字呀？说给我听，或者点下面的框打出来。")
    }

    fun onOptionClick(label: String) {
        when (ui.step) {
            Step.WELCOME -> if (label == "开始") goVoicePrep()
            Step.CONFIRM_NAME -> onConfirmName(label == "对")
            Step.GUIDE_START -> finish(startNow = label == "开始学习")
            Step.VOICE_PREP -> when (label) {
                "下载语音包" -> startVoiceDownload()
                "重试" -> startVoiceDownload()
                "暂时用系统语音" -> useSystemVoice()
                else -> {}
            }
            Step.ASK_NAME -> if (label == "先不录名字") goGuideStart()   // 跳过姓名 → finishDirect
            else -> {}
        }
    }

    fun onTypedName(name: String) {
        val clean = name.trim().filter { it in '\u4e00'..'\u9fff' }.take(4)
        if (clean.isEmpty()) return
        ui = ui.copy(pendingName = clean)
        when (ui.step) {
            Step.ASK_NAME -> goConfirmName()
            Step.FALLBACK_INPUT -> goGuideStart()
            else -> {}
        }
    }

    fun onConfirmName(yes: Boolean) {
        if (yes) {
            goGuideStart()
        } else {
            val attempts = ui.confirmAttempts + 1
            ui = ui.copy(confirmAttempts = attempts)
            if (attempts >= 3) goFallbackInput()
            else goAskName("没关系，我们再试一次。你叫什么名字？说给我听，或者点下面的框。")
        }
    }

    /** 建档落库 + 完成（startNow：立即进学习页 / 回首页）。仅当已录姓名。 */
    private fun finish(startNow: Boolean) {
        val name = ui.pendingName
        if (name.isEmpty()) { finishDirect(); return }
        val selected = Mascots.candidates[ui.mascotIndex]
        ui = ui.copy(step = Step.DONE, robotText = "好！那我们开始吧！")
        // Room 写入必须在 IO 线程；完成后回调 UI 导航
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                settings.mascotId = selected.variant.id
                settings.onboardingDone = true
                val chars = name.map { it.toString() }
                store.namePlan = com.literacy.agent.model.NamePlan(
                    fullName = name,
                    targetChars = chars,
                    priorityMode = "soft",
                )
                chars.forEach { c ->
                    val rec = store.getCharacter(c)
                    store.upsertCharacter(rec.copy(source = "name_plan"))
                }
            }
            onComplete?.invoke(name, startNow)
        }
    }

    /** 未录姓名/跳过引导：完成引导但不建档（首页建档卡引导，随时可补）。 */
    private fun finishDirect() {
        val selected = Mascots.candidates[ui.mascotIndex]
        ui = ui.copy(step = Step.DONE, robotText = "好！那我们先逛一逛，想录名字的时候随时说“建档”。")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                settings.mascotId = selected.variant.id
                settings.onboardingDone = true
            }
            onComplete?.invoke("", false)
        }
    }

    /** 开始下载语音包（TTS 女声 + STT 识别，~210MB，建议 Wi-Fi）。不可跳过，失败可重试。 */
    fun startVoiceDownload() {
        if (ui.voiceDownloading) return
        ui = ui.copy(voiceDownloading = true, voiceDownloadProgress = 0)
        viewModelScope.launch {
            try {
                val mm = VoiceHub.modelManager
                // 分两段下载：TTS 先（女声朗读立即可用），再 STT
                if (!mm.ttsReady()) mm.downloadTts { p ->
                    ui = ui.copy(voiceDownloadProgress = p / 2)
                }
                if (!mm.sttReady()) mm.downloadStt { p ->
                    ui = ui.copy(voiceDownloadProgress = 50 + p / 2)
                }
                // 加载离线引擎
                VoiceHub.offline.initTts()
                VoiceHub.offline.initStt()
                ui = ui.copy(voiceDownloading = false, voiceDownloadProgress = 100, voiceDownloadDone = true, voiceModelsReady = true)
                goPickMascot()
            } catch (e: Exception) {
                // 下载失败：计数，提供重试；2 次后出现弱化"暂时用系统语音"出口
                val failCount = ui.voiceFailCount + 1
                ui = ui.copy(
                    voiceDownloading = false,
                    voiceFailCount = failCount,
                    voiceError = e.message ?: e.javaClass.simpleName,
                    robotText = if (failCount >= 2) "下载失败。检查一下网络和 Wi-Fi，再试一次。" else "下载失败了，检查一下网络，然后重试。",
                )
            }
        }
    }

    /** 失败 2 次后的弱化出口：暂时用系统语音（下载完成自动切回离线；首页语音包卡常驻提醒）。 */
    fun useSystemVoice() {
        if (ui.voiceDownloading) return
        goPickMascot()
    }

    // ── 语音解析（本地规则，不依赖 LLM） ────────────────────────────
    private fun pickMascotIndex(text: String): Int? = OnboardingVoiceRules.pickMascotIndex(text)

    private fun extractName(text: String): String = OnboardingVoiceRules.extractName(text)

    private fun isNo(text: String): Boolean = OnboardingVoiceRules.isNo(text)

    private fun isYes(text: String): Boolean = OnboardingVoiceRules.isYes(text)
}

/** 引导语音解析规则（纯 Kotlin，可 JVM 单元测试）。 */
object OnboardingVoiceRules {
    /** "第N个" → 角色下标（0-based）；无匹配 null。 */
    fun pickMascotIndex(text: String): Int? {
        val cn = mapOf("一" to 1, "两" to 2, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "七" to 7, "八" to 8)
        val m = Regex("第([一二两三四五六七八1-8])个").find(text.trim()) ?: return null
        val g = m.groupValues[1]
        val n = cn[g] ?: g.toIntOrNull() ?: return null
        return if (n in 1..Mascots.candidates.size) n - 1 else null
    }

    /** 从语音转写提取姓名（去前缀 + 汉字，取前 4 字）。 */
    fun extractName(text: String): String {
        var t = text.trim().trimEnd('。', '！', '？', '.', '!', '?')
        for (prefix in listOf("我叫", "我的名字是", "名字叫", "我的名字叫", "我是", "我叫作")) {
            if (t.startsWith(prefix)) { t = t.removePrefix(prefix); break }
        }
        return t.filter { it in '\u4e00'..'\u9fff' }.take(4)
    }

    fun isNo(text: String): Boolean =
        text.contains("不对") || text.contains("不是") || text.contains("错了") || text.contains("没有") || text.contains("不是这个")

    fun isYes(text: String): Boolean {
        if (isNo(text)) return false   // "不对"/"不是" 含"对"/"是"，必须先排除否定
        return text.contains("对") || text.contains("是") || text.contains("嗯") || text.contains("没错") || text.contains("好")
    }
}

/** OnboardingViewModel 工厂（settings/store 注入）。 */
class OnboardingViewModelFactory(
    private val settings: AppSettings,
    private val store: com.literacy.agent.store.LearningStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        OnboardingViewModel(settings, store) as T
}
