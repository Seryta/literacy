package com.literacy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.agent.data.HanziDataSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * 首页：选字开始学习（+ key 状态提示 + 设置入口）。
 * 首版简单入口：输入想学的字 / 推荐字一键开始；建档与姓名优先后续迭代。
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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("识字助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("学会认读写自己的名字和常用字", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        // 建档状态
        if (namePlan != null && namePlan.targetChars.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("我的名字：${namePlan.fullName}", fontWeight = FontWeight.Bold)
                    Text("字包：${namePlan.targetChars.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        namePlan.targetChars.forEach { c ->
                            OutlinedButton(
                                onClick = { onStartLearning(c) },
                                modifier = Modifier.height(44.dp),
                            ) { Text(c, fontSize = 18.sp) }
                        }
                    }
                    TextButton(onClick = onOpenProfile) { Text("修改姓名") }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("✦ 还没有建档", fontWeight = FontWeight.Bold)
                    Text("先录入姓名，从学会自己的名字开始。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) { Text("开始建档 →") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // P2-4：今日复习队列提示（P1-1 排期后队列非空）
        if (reviewQueueSize > 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("📚 今天有 $reviewQueueSize 个字待复习", fontWeight = FontWeight.Bold)
                    Text("先复习巩固，再学新字效果更好。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // key 状态提示
        if (!hasApiKey) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("⚠ 未配置 API Key", fontWeight = FontWeight.Bold)
                    Text("AI 老师无法工作，请先到设置页填写。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSettings) { Text("去设置 →") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 输入想学的字
        OutlinedTextField(
            value = input,
            onValueChange = { input = it; error = null },
            label = { Text("输入想学的字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
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
                .padding(top = 8.dp),
        ) { Text("开始学习") }

        // 开发模式（仅 debug 构建）：直达各阶段，便于测试独立写/复习等流程
        if (com.literacy.app.BuildConfig.DEBUG) {
            Spacer(Modifier.height(8.dp))
            Text("开发模式（debug 构建）", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("guided_write" to "直达跟写", "independent_write" to "直达独立写", "review" to "直达复习").forEach { (stage, label) ->
                OutlinedButton(
                    onClick = { onStartLearning("家:$stage") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) { Text("$label「家」") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("推荐字（点一下开始）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        // review-09 P2-6：推荐字可用性检查移出重组——IO 后台算（首次触发 18MB 字库哈希/复制
        // + SQLite 查询，不能在 Compose 主线程做）
        var available by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            available = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recommended.filter { hanzi.find(it) != null }
            }
        }
        available.forEach { char ->
            OutlinedButton(
                onClick = { onStartLearning(char) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                Text("$char", fontSize = 20.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("设置") }
    }
}
