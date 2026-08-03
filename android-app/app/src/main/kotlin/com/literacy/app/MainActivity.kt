package com.literacy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.literacy.agent.data.HanziDataSource
import com.literacy.app.data.AssetHanziDataSource
import com.literacy.app.data.room.AppDatabase
import com.literacy.app.data.room.RoomStore
import com.literacy.app.settings.AppSettings
import com.literacy.app.ui.*
import com.literacy.app.ui.theme.LiteracyTheme
import kotlinx.coroutines.launch

/** 顶层导航：首页（选字）→ 建档 / 学习 / 设置 / 关于 / 角色选择。 */
private enum class Screen { HOME, SETTINGS, LEARN, PROFILE, ABOUT, MASCOT }

/** 入口 Activity：装配字库 + 设置，导航路由 + 全局悬浮吉祥物（语音）。 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private lateinit var hanzi: HanziDataSource
    private lateinit var store: com.literacy.agent.store.LearningStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        // api_key 加密存储懒创建涉及 Keystore（首次 ~100ms 级）：后台预热，避免首帧/进学习卡顿。
        // 异常不外抛：prewarm 内部降级（Keystore 损坏/定制 ROM 兼容时 api_key 不可用，不 kill 进程）
        Thread { settings.prewarm() }.start()
        hanzi = AssetHanziDataSource(this)
        store = RoomStore(AppDatabase.get(this))

        setContent {
            LiteracyTheme {
                Surface {
                    LiteracyApp(settings, hanzi, store)
                }
            }
        }
    }
}

@Composable
private fun LiteracyApp(settings: AppSettings, hanzi: HanziDataSource, store: com.literacy.agent.store.LearningStore) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var learnChar by remember { mutableStateOf("家") }
    var enterCount by rememberSaveable { mutableStateOf(0) }   // P1-16 + review-10 P1-11：旋转保留计数（Activity 重建不重置）
    var mascot by remember {
        mutableStateOf(Mascots.candidates.firstOrNull { it.variant.id == settings.mascotId } ?: Mascots.default)
    }
    // 首次引导检测：onboardingDone 未置位且无建档数据 → 走引导流程（老用户自动跳过）
    var needOnboarding by remember { mutableStateOf(!settings.onboardingDone) }
    LaunchedEffect(Unit) {
        if (needOnboarding) {
            val hasPlan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.namePlan != null }
            if (hasPlan) needOnboarding = false
        }
    }

    // ── 全局语音：悬浮吉祥物 + 系统 STT + 按页面分发 ──
    val context = LocalContext.current
    val speech = remember { SpeechInputManager(context) }
    DisposableEffect(Unit) { onDispose { speech.destroy() } }
    var listening by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 语音转写结果按当前页面的处理器分发（各分支重组时设置）
    val voiceHandler = remember { mutableStateOf<(String) -> Unit>({}) }
    // 吉祥物气泡提示（各分支重组时设置）
    val bubbleText = remember { mutableStateOf<String?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!needOnboarding) {
                MascotBall(
                    mascot = mascot,
                    listening = listening,
                    bubbleText = bubbleText.value,
                    onClick = {
                        if (listening) {
                            speech.cancel()
                            listening = false
                        } else {
                            val ok = speech.start { outcome ->
                                listening = false
                                bubbleText.value = null
                                when (outcome) {
                                    is SpeechInputManager.SpeechOutcome.Text -> voiceHandler.value(outcome.text)
                                    is SpeechInputManager.SpeechOutcome.Error ->
                                        scope.launch { snackbarHostState.showSnackbar(outcome.message) }
                                }
                            }
                            if (ok) listening = true
                            else scope.launch { snackbarHostState.showSnackbar("语音服务不可用") }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (needOnboarding) {
                // ── 首次引导：活泼机器人对话建档（本地驱动，不依赖 API key）──
                val obVm: OnboardingViewModel = viewModel(
                    key = "onboarding",
                    factory = OnboardingViewModelFactory(settings, store),
                )
                voiceHandler.value = { text -> obVm.handleVoice(text) }
                bubbleText.value = null
                obVm.onComplete = { name, startNow ->
                    mascot = Mascots.candidates.firstOrNull { it.variant.id == settings.mascotId } ?: Mascots.default
                    needOnboarding = false
                    if (startNow) {
                        learnChar = name.firstOrNull()?.toString() ?: "家"
                        enterCount++
                        screen = Screen.LEARN
                    } else {
                        screen = Screen.HOME
                    }
                }
                OnboardingScreen(
                    viewModel = obVm,
                    listening = listening,
                    onSpeak = {
                        if (listening) {
                            speech.cancel(); listening = false
                        } else {
                            val ok = speech.start { outcome ->
                                listening = false
                                when (outcome) {
                                    is SpeechInputManager.SpeechOutcome.Text -> obVm.handleVoice(outcome.text)
                                    is SpeechInputManager.SpeechOutcome.Error ->
                                        scope.launch { snackbarHostState.showSnackbar(outcome.message) }
                                }
                            }
                            if (ok) listening = true
                            else scope.launch { snackbarHostState.showSnackbar("语音服务不可用") }
                        }
                    },
                    onSkip = {
                        settings.onboardingDone = true
                        needOnboarding = false
                    },
                )
            } else {
            when (screen) {
                Screen.HOME -> {
                    // Room 禁止主线程查询：异步读取 namePlan
                    var namePlan by remember { mutableStateOf<com.literacy.agent.model.NamePlan?>(null) }
                    var reviewQueueSize by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        val s = store
                        namePlan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { s.namePlan }
                        reviewQueueSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.literacy.agent.learning.SessionLifecycle(s).buildReviewQueue(java.time.LocalDate.now()).size
                        }
                    }
                    // 首页语音命令：设置 / 建档 / 学X字（语音控制界面操作）
                    voiceHandler.value = { text ->
                        when (val action = VoiceCommandParser.parse(text)) {
                            is VoiceCommandParser.Action.OpenSettings -> screen = Screen.SETTINGS
                            is VoiceCommandParser.Action.OpenProfile -> screen = Screen.PROFILE
                            is VoiceCommandParser.Action.LearnChar -> {
                                if (hanzi.find(action.c) != null) {
                                    learnChar = action.c
                                    enterCount++
                                    screen = Screen.LEARN
                                } else scope.launch { snackbarHostState.showSnackbar("字库没有「${action.c}」，换个字试试") }
                            }
                            is VoiceCommandParser.Action.Unknown ->
                                scope.launch { snackbarHostState.showSnackbar("没听懂，试试说：我想学家") }
                        }
                    }
                    bubbleText.value = null
                    HomeScreen(
                        hanzi = hanzi,
                        hasApiKey = settings.hasApiKey,
                        namePlan = namePlan,
                        reviewQueueSize = reviewQueueSize,
                        mascot = mascot,
                        displayName = settings.displayName,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenProfile = { screen = Screen.PROFILE },
                        onStartLearning = { char ->
                            learnChar = char
                            enterCount++   // review-09 P1-11：每次进入学习页新 VM（同字重入不复用 ended/paused/旧配置）
                            screen = Screen.LEARN
                        },
                    )
                }
                Screen.PROFILE -> {
                    voiceHandler.value = {}   // 建档页不接收语音导航
                    bubbleText.value = null
                    ProfileScreen(
                        store = store,
                        hanzi = hanzi,
                        settings = settings,
                        onComplete = { screen = Screen.HOME },
                    )
                }
                Screen.SETTINGS -> {
                    voiceHandler.value = {
                        if (it.contains("返回") || it.contains("回去") || it.contains("退出")) screen = Screen.HOME
                    }
                    bubbleText.value = null
                    SettingsScreen(
                        settings = settings,
                        onBack = { screen = Screen.HOME },
                        onOpenAbout = { screen = Screen.ABOUT },
                        onOpenMascot = { screen = Screen.MASCOT },
                        onDebugStartLearning = { char ->
                            learnChar = char
                            enterCount++   // 与首页入口一致：每次进入新 VM
                            screen = Screen.LEARN
                        },
                    )
                }
                Screen.ABOUT -> {
                    voiceHandler.value = {
                        if (it.contains("返回") || it.contains("回去")) screen = Screen.SETTINGS
                    }
                    bubbleText.value = null
                    AboutScreen(onBack = { screen = Screen.SETTINGS })
                }
                Screen.MASCOT -> {
                    voiceHandler.value = {
                        if (it.contains("返回") || it.contains("回去")) screen = Screen.SETTINGS
                    }
                    bubbleText.value = null
                    MascotGalleryScreen(
                        current = mascot,
                        onSelect = {
                            mascot = it
                            scope.launch { snackbarHostState.showSnackbar("已选「${it.variant.label}」") }
                        },
                        onBack = { screen = Screen.SETTINGS },
                    )
                }
                Screen.LEARN -> {
                    // 换字时重建会话（review-05 P2-2：key 不含 apiKey 敏感信息）
                    val vm: LearnViewModel = viewModel(
                        key = "learn:$learnChar:$enterCount",   // P1-16：每次进入新 VM，provider 用最新配置
                        factory = LearnViewModelFactory(settings, hanzi, store),
                    )
                    // 学习页语音全控制：操作命令（帮助/跳过/暂停/继续/结束/复习控制）→ 按钮；
                    // 其余转写文本 → 教学管线（本地意图解析 + LLM）
                    voiceHandler.value = { text ->
                        val cmd = VoiceCommandParser.learnCommand(text)
                        if (cmd != null) vm.onButton(cmd) else vm.onUserInput(text)
                    }
                    // 吉祥物气泡：跟随时机性提示（loading/paused/阶段）
                    val ui = vm.ui   // Compose state，重组自动追踪
                    bubbleText.value = when {
                        ui.loading -> "老师想想…"
                        ui.paused -> "点我说「继续」"
                        ui.sessionEnded -> "学完啦，点我说「结束」"
                        else -> null
                    }
                    LaunchedEffect(Unit) {
                        vm.bindTts(context.applicationContext)
                        if (vm.ui.char != learnChar) vm.startLearning(learnChar)
                    }
                    LearnScreen(
                        viewModel = vm,
                        onBack = {
                            vm.releaseTts()   // review-09 P1-11：离开释放 TTS（Activity 级 VM 不随 composable 销毁）
                            vm.cancelInFlight()   // review-10 P1-11：离页取消在途请求
                            screen = Screen.HOME
                        },
                    )
                }
            }
            }
        }
    }
}
