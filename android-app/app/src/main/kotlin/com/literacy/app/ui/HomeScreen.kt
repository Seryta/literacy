package com.literacy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.agent.data.HanziDataSource
import com.literacy.app.ui.theme.LiteracyDimens
import kotlinx.coroutines.launch

/**
 * 首页：极简卡片式（目标用户不认识字——东西少、每卡只做一件事）。
 * - 卡片点击 = 朗读卡片内容（TTS 点读，页面文字都说给用户听）
 * - 卡片内按钮 = 直接操作；语音说卡片名 = 操作
 * - 默认路径：学我的名字（认识与写）
 */
@Composable
fun HomeScreen(
    hanzi: HanziDataSource,
    hasApiKey: Boolean,
    namePlan: com.literacy.agent.model.NamePlan?,
    reviewQueueSize: Int = 0,   // P2-4：今天待复习字数量
    mascot: Mascot = Mascots.default,
    displayName: String? = null,   // 用户设置的称呼（settings.displayName）
    searchSignal: Int = 0,   // 语音"想学一个字"触发搜索卡展开
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onStartLearning: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tts = remember { LocalTts(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    /** 点读：点击卡片朗读其内容（不识字用户靠听）。 */
    val readOut: (String) -> Unit = { text -> tts.speak(text) }

    var searchExpanded by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf<String?>(null) }
    // 语音"想学一个字" → 展开搜索卡
    LaunchedEffect(searchSignal) {
        if (searchSignal > 0) {
            searchExpanded = true
            readOut("想学一个字？说出你想学的字，或者让家人帮你输入。")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiteracyDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 顶栏：品牌左 + 设置右上角 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("字", color = MaterialTheme.colorScheme.onPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("识字助手", style = MaterialTheme.typography.titleLarge)
                Text(
                    "学会认读写自己的名字和常用字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(
                onClick = { readOut("设置。"); onOpenSettings() },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", modifier = Modifier.size(26.dp))
            }
        }

        // ── 卡片 1：学我的名字（默认路径，最突出）──
        Card(
            onClick = { readOut("学我的名字。从认识、会写自己的名字开始。") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📖", fontSize = 26.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("学我的名字", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (namePlan?.fullName.isNullOrBlank()) "从认识、会写自己的名字开始" else "「${namePlan?.fullName}」认识与写",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        val first = namePlan?.targetChars?.firstOrNull() ?: "家"
                        readOut("好，我们开始学你的名字。")
                        onStartLearning(first)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text("开始学习", fontSize = 20.sp) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "点这里听：学我的名字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 卡片 2：想学一个字（点击展开搜索/输入）──
        Card(
            onClick = {
                searchExpanded = !searchExpanded
                readOut(if (searchExpanded) "说出你想学的字，或者让家人帮你输入。" else "想学一个字。")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🔍", fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("想学一个字", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "说出你想学的字，或让家人帮你输入",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (searchExpanded) "▲" else "▼", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 展开：输入 + 推荐候选
                if (searchExpanded) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = searchInput,
                        onValueChange = { searchInput = it; searchError = null },
                        label = { Text("输入想学的字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                    )
                    searchError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val c = searchInput.trim().take(1)
                            when {
                                c.isEmpty() -> searchError = "请输入一个字"
                                hanzi.find(c) == null -> searchError = "字库没有「$c」，换个字试试"
                                else -> onStartLearning(c)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("开始学这个字", fontSize = 18.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text("或直接说：我想学家（语音一直在听）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 卡片 3：复习（有队列时显示）──
        if (reviewQueueSize > 0) {
            Card(
                onClick = {
                    readOut("今天有 $reviewQueueSize 个字待复习。")
                    onStartLearning("${namePlan?.targetChars?.firstOrNull() ?: "家"}:review")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(
                    Modifier.padding(LiteracyDimens.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📚", fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("今天有 $reviewQueueSize 个字待复习", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("先复习巩固，再学新字效果更好", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text("去复习 →", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── 未建档弱提示 ──
        if (namePlan == null || namePlan.targetChars.isEmpty()) {
            Card(
                onClick = onOpenProfile,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(
                    Modifier.padding(LiteracyDimens.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✏️", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("还没有建档", style = MaterialTheme.typography.titleMedium)
                        Text("先录入名字，从学会写自己的名字开始", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("去建档 →", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── 首次配置提示（弱）──
        if (!hasApiKey) {
            Text(
                "首次使用，请先点右上角设置，让家人帮忙配置语音老师",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // 底部安全区：悬浮吉祥物 + 系统导航条留位
        Spacer(Modifier.height(96.dp))

        // ── debug：语音包下载验证入口（临时）──
        if (com.literacy.app.BuildConfig.DEBUG) {
            var dlProgress by remember { mutableStateOf(-1) }
            var dlMsg by remember { mutableStateOf("") }
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("开发：语音包下载测试", style = MaterialTheme.typography.titleSmall)
                    Text("TTS就绪=${com.literacy.app.ui.voice.VoiceHub.modelManager.ttsReady()} STT就绪=${com.literacy.app.ui.voice.VoiceHub.modelManager.sttReady()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dlProgress >= 0) {
                        Text("下载 $dlProgress%", style = MaterialTheme.typography.bodySmall)
                    }
                    dlMsg.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Button(
                        onClick = {
                            kotlinx.coroutines.MainScope().launch {
                                try {
                                    val mm = com.literacy.app.ui.voice.VoiceHub.modelManager
                                    if (!mm.ttsReady()) mm.downloadTts { dlProgress = it }
                                    if (!mm.sttReady()) mm.downloadStt { dlProgress = it }
                                    com.literacy.app.ui.voice.VoiceHub.offline.initTts()
                                    com.literacy.app.ui.voice.VoiceHub.offline.initStt()
                                    dlMsg = "下载完成 TTS=${com.literacy.app.ui.voice.VoiceHub.offlineTtsReady} STT=${com.literacy.app.ui.voice.VoiceHub.offlineSttReady}"
                                } catch (e: Exception) {
                                    dlMsg = "失败: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("下载语音包（mock）") }
                }
            }
        }
    }
}
