package com.literacy.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    // 麦克风运行时权限（引导/学习自动语音需要；拒绝则回落手动按钮）
    var audioGranted by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioGranted = granted
        if (!granted) scope.launch { snackbarHostState.showSnackbar("需要麦克风权限才能语音对话") }
    }
    // 学习页实时字幕（自动监听 partial）
    var learnPartial by remember { mutableStateOf("") }
    // 自动监听启动器（引导/学习页共用）：连续监听 + 实时字幕 + 硬错误降级
    val startAutoListen = remember {
        { onText: (String) -> Unit, onPartial: (String) -> Unit ->
            speech.start(
                callback = { outcome ->
                    when (outcome) {
                        is SpeechInputManager.SpeechOutcome.Text -> onText(outcome.text)
                        is SpeechInputManager.SpeechOutcome.Error -> {
                            listening = false
                            scope.launch { snackbarHostState.showSnackbar(outcome.message) }
                        }
                    }
                },
                autoRestart = true,
                onPartial = onPartial,
            )
        }
    }
    // 当前页的自动监听重启器（悬浮球点击时调用：让用户立即说话）；非自动监听页为空 → 悬浮球走一次性识别
    val autoListenRestart = remember { mutableStateOf<(() -> Unit)?>(null) }
    // 后台生命周期：退后台停监听（隐私+电池——老人不会自己关麦克风），回前台自动监听页恢复
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    speech.cancel()
                    listening = false
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    autoListenRestart.value?.invoke()   // 学习页/引导：回前台恢复自动监听
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!needOnboarding) {
                MascotBall(
                    mascot = mascot,
                    listening = listening,
                    bubbleText = bubbleText.value,
                    onClick = {
                        // 自动监听页（学习/引导）：重启监听让用户立即说话；其他页：一次性识别
                        val restart = autoListenRestart.value
                        if (restart != null) {
                            speech.cancel()
                            restart()
                        } else if (!listening) {
                            val ok = speech.start(
                                callback = { outcome ->
                                    listening = false
                                    bubbleText.value = null
                                    when (outcome) {
                                        is SpeechInputManager.SpeechOutcome.Text -> voiceHandler.value(outcome.text)
                                        is SpeechInputManager.SpeechOutcome.Error ->
                                            scope.launch { snackbarHostState.showSnackbar(outcome.message) }
                                    }
                                },
                            )
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
                // 引导阶段自动语音：进来就持续听，无需点击（连续监听模式）
                // 先请求麦克风权限，授权后自动启动监听
                LaunchedEffect(Unit) {
                    if (!audioGranted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                val obOnText: (String) -> Unit = { obVm.handleVoice(it) }
                LaunchedEffect(audioGranted) {
                    if (audioGranted && !listening) {
                        val ok = startAutoListen(obOnText, { obVm.onPartial(it) })
                        listening = ok
                    }
                }
                autoListenRestart.value = {
                    val ok = startAutoListen(obOnText, { obVm.onPartial(it) })
                    listening = ok
                }
                val stopAutoListen = {
                    autoListenRestart.value = null
                    speech.cancel()
                    listening = false
                }
                obVm.onComplete = { name, startNow ->
                    stopAutoListen()
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
                    onSkip = {
                        stopAutoListen()
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
                    var searchSignal by remember { mutableStateOf(0) }   // 语音"想学一个字"→ 搜索卡展开信号
                    // 首页语音命令：学我的名字 / 想学一个字 / 复习 / 设置 / 建档 / 学X字
                    val homeOnText: (String) -> Unit = { text ->
                        when (val action = VoiceCommandParser.parse(text)) {
                            is VoiceCommandParser.Action.OpenSettings -> screen = Screen.SETTINGS
                            is VoiceCommandParser.Action.OpenProfile -> screen = Screen.PROFILE
                            is VoiceCommandParser.Action.OpenNameLearning -> {
                                val first = namePlan?.targetChars?.firstOrNull() ?: "家"
                                learnChar = first
                                enterCount++
                                screen = Screen.LEARN
                            }
                            is VoiceCommandParser.Action.OpenSearchChar -> searchSignal++   // 展开搜索卡（语音问"想学什么字"）
                            is VoiceCommandParser.Action.OpenReview -> {
                                learnChar = "${namePlan?.targetChars?.firstOrNull() ?: "家"}:review"
                                enterCount++
                                screen = Screen.LEARN
                            }
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
                    voiceHandler.value = homeOnText
                    // 由我们主导：进入首页自动听 + 机器人主动问今天学什么
                    val homeTts = remember { LocalTts(context) }
                    DisposableEffect(Unit) {
                        onDispose {
                            homeTts.shutdown()
                            speech.cancel()   // 离开首页停监听（避免与学习/设置页冲突）
                            listening = false
                        }
                    }
                    LaunchedEffect(Unit) {
                        if (!audioGranted) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    LaunchedEffect(audioGranted) {
                        if (audioGranted && !listening) {
                            val ok = startAutoListen(homeOnText, {})
                            listening = ok
                        }
                    }
                    autoListenRestart.value = {
                        val ok = startAutoListen(homeOnText, {})
                        listening = ok
                    }
                    // 进入首页延迟主动招呼（陪伴感：不识字用户不会被"静默等待"困住）
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(1500)
                        if (screen == Screen.HOME) {
                            bubbleText.value = "今天想学什么？直接说，比如：我想学家"
                            homeTts.speak("今天想学什么？直接告诉我，比如：我想学家。")
                        }
                    }
                    HomeScreen(
                        hanzi = hanzi,
                        hasApiKey = settings.hasApiKey,
                        namePlan = namePlan,
                        reviewQueueSize = reviewQueueSize,
                        mascot = mascot,
                        displayName = settings.displayName,
                        searchSignal = searchSignal,
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
                            settings.mascotId = it.variant.id   // 持久化：重启后保持选择
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
                    val learnOnText: (String) -> Unit = { text ->
                        learnPartial = ""
                        val cmd = VoiceCommandParser.learnCommand(text)
                        if (cmd != null) vm.onButton(cmd) else vm.onUserInput(text)
                    }
                    voiceHandler.value = learnOnText
                    // 学习页自动语音（主场景，与引导一致：自动听 + 实时字幕）
                    LaunchedEffect(Unit) {
                        if (!audioGranted) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    LaunchedEffect(audioGranted) {
                        if (audioGranted && !listening) {
                            val ok = startAutoListen(learnOnText, { learnPartial = it })
                            listening = ok
                        }
                    }
                    autoListenRestart.value = {
                        val ok = startAutoListen(learnOnText, { learnPartial = it })
                        listening = ok
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
                        partialText = learnPartial,
                        onBack = {
                            autoListenRestart.value = null
                            speech.cancel()   // 离开学习页停自动监听
                            listening = false
                            learnPartial = ""   // 离页清字幕，避免下次进入显示旧字幕
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
