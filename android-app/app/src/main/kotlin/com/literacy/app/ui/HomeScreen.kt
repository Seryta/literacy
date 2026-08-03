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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.agent.data.HanziDataSource
import com.literacy.app.ui.theme.LiteracyDimens

// 黑板场景色（现代教学感：深墨绿黑板 + 粉笔白字）
private val ChalkboardTop = Color(0xFF2E4B44)
private val ChalkboardBottom = Color(0xFF223631)
private val ChalkWhite = Color(0xFFF5F1E4)
private val ChalkSoft = Color(0xFFC7D8CF)

/**
 * 首页：顶栏（品牌 + 右上角设置）→ 教学黑板主场景 → 学字入口。
 * 面向不识字的中老年用户：大字号、大按钮、分层清晰、少文字。
 * 开发模式入口不在首页（挪到设置页 debug 区），普通用户永远见不到。
 */
@Composable
fun HomeScreen(
    hanzi: HanziDataSource,
    hasApiKey: Boolean,
    namePlan: com.literacy.agent.model.NamePlan?,
    reviewQueueSize: Int = 0,   // P2-4：今天待复习字数量
    mascot: Mascot = Mascots.default,
    displayName: String? = null,   // 用户设置的称呼（settings.displayName）
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onStartLearning: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val recommended = listOf("家", "国", "爱", "张", "王", "李", "陈", "好", "学", "天")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LiteracyDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 顶栏：品牌左 + 设置右上角（独立图标按钮）──
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
            // 设置独立右上角按钮（现代 App 惯例，不再混在页面底部）
            FilledTonalIconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", modifier = Modifier.size(26.dp))
            }
        }

        // ── 教学黑板主场景：老师 + 黑板（教学感，不再是工具卡片流）──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(ChalkboardTop, ChalkboardBottom)), MaterialTheme.shapes.extraLarge),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 老师（吉祥物）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            MascotAvatar(variant = mascot.variant, size = 46.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (displayName.isNullOrBlank()) "你好，我是${mascot.variant.label}，你的识字小伙伴" else "$displayName，你好呀",
                            color = ChalkWhite,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    if (namePlan != null && namePlan.targetChars.isNotEmpty()) {
                        // 已建档：黑板写名字 + 名字字包可直接点
                        Text("你的名字", color = ChalkSoft, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(namePlan.fullName, color = ChalkWhite, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            namePlan.targetChars.forEach { c ->
                                Button(
                                    onClick = { onStartLearning(c) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp),
                                    shape = MaterialTheme.shapes.large,
                                    colors = ButtonDefaults.buttonColors(containerColor = ChalkWhite, contentColor = ChalkboardBottom),
                                ) { Text(c, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onOpenProfile) {
                            Text("修改名字", color = ChalkSoft, fontSize = 16.sp)
                        }
                    } else {
                        // 未建档：黑板引导建档
                        Text("今天想学什么？", color = ChalkWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("从学会写自己的名字开始", color = ChalkSoft, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = onOpenProfile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(LiteracyDimens.ActionButtonHeight),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE8A33D), contentColor = Color(0xFF2A1A00)),
                        ) { Text("教我写名字", fontSize = 20.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── 复习提示（P2-4 排期队列）──
        if (reviewQueueSize > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    Modifier.padding(LiteracyDimens.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📚", fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("今天有 $reviewQueueSize 个字待复习", style = MaterialTheme.typography.titleMedium)
                        Text("先复习巩固，再学新字效果更好", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { onStartLearning("${namePlan?.targetChars?.firstOrNull() ?: "家"}:review") },   // review：进复习模式（不是学习模式）
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("去复习") }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ── 学字入口（白卡，功能性）──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Text("想学别的字？", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "不会打字也没关系，让家人帮忙输入，或者直接对宠物说",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text("输入想学的字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val c = input.trim().take(1)
                        when {
                            c.isEmpty() -> error = "请输入一个字"
                            hanzi.find(c) == null -> error = "字库未收录「$c」，换个字试试"
                            else -> onStartLearning(c)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                ) { Text("开始学习", fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.height(18.dp))

        // ── 推荐字：2 列大按钮网格 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("推荐字", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("点一下就开始", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        // review-09 P2-6：推荐字可用性检查移出重组——IO 后台算（首次触发 18MB 字库哈希/复制
        // + SQLite 查询，不能在 Compose 主线程做）
        var available by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            available = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recommended.filter { hanzi.find(it) != null }
            }
        }
        available.chunked(2).forEach { rowChars ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowChars.forEach { char ->
                    OutlinedButton(
                        onClick = { onStartLearning(char) },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(char, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // 首次配置提示：弱提示，不阻断主流程（配在设置里）
        if (!hasApiKey) {
            Text(
                "首次使用，请先点右上角设置，让家人帮忙配置语音老师",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // 底部安全区：悬浮吉祥物 + 系统导航条留位（避免推荐字被遮挡）
        Spacer(Modifier.height(96.dp))
    }
}
