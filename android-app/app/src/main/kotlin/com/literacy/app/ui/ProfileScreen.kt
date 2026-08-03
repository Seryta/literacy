package com.literacy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.literacy.agent.data.HanziDataSource
import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.store.LearningStore
import com.literacy.app.ui.theme.LiteracyDimens

/**
 * 建档页：录入姓名 → 逐字拆解（字库校验）→ 生成 name_plan。
 * 姓名是 P0 字包（DESIGN：先学会认写自己的名字）。
 */
@Composable
fun ProfileScreen(
    store: LearningStore,
    hanzi: HanziDataSource,
    settings: com.literacy.app.settings.AppSettings,
    onComplete: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var savedSummary by remember { mutableStateOf<String?>(null) }   // P1-15：摘要存 state，主线程不读 Room
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LiteracyDimens.ScreenPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text("建档", style = MaterialTheme.typography.headlineLarge)
        Text("先学会认写自己的名字（姓名是第一个字包）",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(LiteracyDimens.CardPadding)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("学习者姓名（如：张建国）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("称呼方式（可选，如：张阿姨）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = MaterialTheme.shapes.small,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val chars = name.trim()
                        when {
                            chars.isEmpty() -> error = "请输入姓名"
                            chars.length > 4 -> error = "姓名过长（最多 4 字）"
                            else -> {
                                // review-10 P2-16：字库可用性检查移入 IO（首次触发 18MB 哈希/复制，不能在主线程）
                                scope.launch {
                                    val missing = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        chars.map { it.toString() }.filter { hanzi.find(it) == null }
                                    }
                                    if (missing.isNotEmpty()) {
                                        error = "字库未收录：${missing.joinToString("、")}（换个写法或补充字库）"
                                    } else {
                                    // 拆字 → name_plan + characters（source=name_plan），Room 写入在 IO 线程
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val targetChars = chars.map { it.toString() }
                                            // P1-13（review-09）+ review-10 P1-8：保存用户填的"称呼"；
                                            // 留空 = 不设称呼（不再用完整姓名兜底——那会把真实姓名发给外部模型）
                                            settings.displayName = displayName.trim()
                                            store.namePlan = com.literacy.agent.model.NamePlan(
                                                fullName = chars,
                                                targetChars = targetChars,
                                                priorityMode = "soft",
                                            )
                                            targetChars.forEach { c ->
                                                val rec = store.getCharacter(c)
                                                store.upsertCharacter(rec.copy(source = "name_plan"))
                                            }
                                            // P1-15：IO 内计算摘要，主线程不读 Room
                                            targetChars.joinToString("、")
                                        }.let { summary -> savedSummary = summary }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LiteracyDimens.ActionButtonHeight),
                    shape = MaterialTheme.shapes.large,
                ) { Text("保存并开始", fontSize = 20.sp) }

                savedSummary?.let { summary ->
                    Spacer(Modifier.height(10.dp))
                    Text("建档完成！姓名目标：$summary",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = onComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LiteracyDimens.ActionButtonHeight),
                        shape = MaterialTheme.shapes.large,
                    ) { Text("返回首页，开始学习我的名字 →", fontSize = 18.sp) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onComplete) { Text("← 返回", fontSize = 17.sp) }
        Spacer(Modifier.height(16.dp))
    }
}
