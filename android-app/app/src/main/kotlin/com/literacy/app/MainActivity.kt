package com.literacy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.literacy.agent.data.HanziDataSource
import com.literacy.app.data.AssetHanziDataSource
import com.literacy.app.data.room.AppDatabase
import com.literacy.app.data.room.RoomStore
import com.literacy.app.settings.AppSettings
import com.literacy.app.ui.HomeScreen
import com.literacy.app.ui.LearnScreen
import com.literacy.app.ui.LearnViewModel
import com.literacy.app.ui.LearnViewModelFactory
import com.literacy.app.ui.ProfileScreen
import com.literacy.app.ui.SettingsScreen

/** 顶层导航：首页（选字）→ 建档 / 学习 / 设置。 */
private enum class Screen { HOME, SETTINGS, LEARN, PROFILE }

/** 入口 Activity：装配字库 + 设置，导航路由。 */
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
            MaterialTheme {
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
            HomeScreen(
                hanzi = hanzi,
                hasApiKey = settings.hasApiKey,
                namePlan = namePlan,
                reviewQueueSize = reviewQueueSize,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenProfile = { screen = Screen.PROFILE },
                onStartLearning = { char ->
                    learnChar = char
                    enterCount++   // review-09 P1-11：每次进入学习页新 VM（同字重入不复用 ended/paused/旧配置）
                    screen = Screen.LEARN
                },
            )
        }
        Screen.PROFILE -> ProfileScreen(
            store = store,
            hanzi = hanzi,
            settings = settings,
            onComplete = { screen = Screen.HOME },
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onBack = { screen = Screen.HOME },
        )
        Screen.LEARN -> {
            // 换字时重建会话（review-05 P2-2：key 不含 apiKey 敏感信息）
            val vm: LearnViewModel = viewModel(
                key = "learn:$learnChar:$enterCount",   // P1-16：每次进入新 VM，provider 用最新配置
                factory = LearnViewModelFactory(settings, hanzi, store),
            )
            val context = androidx.compose.ui.platform.LocalContext.current
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
