package com.literacy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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

/**
 * 首页：品牌区 + 主操作（建档/名字）+ 复习提示 + 学字入口。
 * 面向不识字的中老年用户：大字号、大按钮、少文字、操作路径单一。
 * 开发模式入口不在首页（挪到设置页 debug 区），普通用户永远见不到。
 */
@Composable
fun HomeScreen(
    hanzi: HanziDataSource,
    hasApiKey: Boolean,
    namePlan: com.literacy.agent.model.NamePlan?,
    reviewQueueSize: Int = 0,   // P2-4：今天待复习字数量
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
            .padding(LiteracyDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        // ── 品牌区：圆形 logo（"字"字）+ 标题 ──
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("字", color = MaterialTheme.colorScheme.onPrimary, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text("识字助手", style = MaterialTheme.typography.headlineLarge)
        Text(
            "学会认读写自己的名字和常用字",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(LiteracyDimens.SectionSpacing))

        // ── 主操作卡：建档（未建档） / 我的名字（已建档）──
        if (namePlan != null && namePlan.targetChars.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                    Text("我的名字", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(namePlan.fullName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("名字里的字：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        namePlan.targetChars.forEach { c ->
                            OutlinedButton(
                                onClick = { onStartLearning(c) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(LiteracyDimens.ActionButtonHeight),
                                shape = MaterialTheme.shapes.large,
                            ) { Text(c, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenProfile) { Text("修改姓名", fontSize = 17.sp) }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                    Text("第一步：写我的名字", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "学会认写自己的名字，是最重要的一步。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onOpenProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LiteracyDimens.ActionButtonHeight),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("教我写名字", fontSize = 20.sp) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 复习提示（P2-4 排期队列）──
        if (reviewQueueSize > 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.padding(LiteracyDimens.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📚", fontSize = 28.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("今天有 $reviewQueueSize 个字待复习", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("先复习巩固，再学新字效果更好", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── 学字入口：输入任意字（也可让家人帮忙输入）──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                Text("想学别的字？", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "不会打字也没关系，可以让家人帮忙输入",
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
        Spacer(Modifier.height(LiteracyDimens.SectionSpacing))

        // ── 推荐字：2 列大按钮网格 ──
        Text("推荐字，点一下就开始", style = MaterialTheme.typography.titleMedium)
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
                            .height(64.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(char, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("设置", fontSize = 18.sp) }
        // 首次配置提示：弱提示，不阻断主流程
        if (!hasApiKey) {
            Spacer(Modifier.height(6.dp))
            Text(
                "首次使用，请让家人帮忙在这里设置语音老师",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
