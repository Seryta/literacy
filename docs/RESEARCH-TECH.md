# Android 技术栈调研

## 当前运行前提

- 当前版本按在线产品设计，教学 session 需要网络和有效的 LLM provider 配置
- LLM Agent 参与教学 turn 的决策和工具调度，不设计离线教学替代流程
- STT、TTS、手写评估和学习数据仍优先在 Android 端本地处理

## UI 框架

**Jetpack Compose**（Kotlin），理由：
- 官方推荐，现代声明式 UI
- Canvas API 适合自定义米字格和手写
- 动画支持好（笔画引导动画）
- Material 3 组件可复用

## 本地存储

**Room**（SQLite 封装），理由：
- Android 官方推荐
- 类型安全，Kotlin 协程友好
- 足够存储学习进度、字库、偏好

## 架构模式

**MVVM + Repository**：
- ViewModel 管理 UI 状态
- Repository 层封装数据来源（Room + Provider Adapter）
- Agent 编排逻辑放在 Domain 层（纯 Kotlin），教学 turn 的决策由接入的 LLM provider 返回

## Provider 适配层

当前更适合采用类似 Pi Agent provider 的抽象层，而不是把单一模型厂商写死在业务逻辑里。

建议 provider 层负责：
- 统一请求与响应结构
- 完整响应优先，流式能力作为后续优化能力封装
- 工具调用协议适配
- provider 配置与鉴权
- 不同模型能力差异的兜底处理

## 手写识别

Android 原生方案：
- **MotionEvent** 记录触摸轨迹（落笔→移动→抬笔）
- 在 Compose Canvas 上渲染笔画
- 笔画数据（坐标序列 + 时间戳）发送给评估模块

笔画评估：
- 本地规则引擎（对比标准笔画特征）
- 简单偏差检测可在本地完成
- 需要 LLM 参与教学判断时，发送本地提取的结构化评估摘要，不上传原始笔画轨迹

## LLM 集成

**OkHttp**：
- 通过 provider adapter 接入具体 LLM
- 首版按"接收完整 JSON → 本地解析校验 → 再触发 TTS 和 toolCall"实现
- 流式输出保留为后续优化项，不作为首版前提
- 网络或 API 不可用时保留当前状态并明确提示重试或结束 session，当前版本不进入离线教学
- Agent 返回的工具调用必须经过本地参数和工具范围校验后执行

## 语音集成

通过 JNI 桥接到 C/C++ 推理引擎：
- **Sherpa-ONNX** 提供 Android 示例工程
- 或分别集成 SenseVoice + CosyVoice 的 ONNX 模型

## 项目结构（建议）

```
app/
├── src/main/java/com/literacy/app/
│   ├── MainActivity.kt
│   ├── ui/
│   │   ├── screen/          # 各页面 Compose
│   │   │   ├── LearnScreen.kt      # 学习主界面
│   │   │   ├── ReviewScreen.kt     # 复习页
│   │   │   └── ProgressScreen.kt   # 进度页
│   │   ├── component/       # 复用组件
│   │   │   ├── MizigeGrid.kt       # 米字格
│   │   │   ├── StrokeGuide.kt      # 笔画引导动画
│   │   │   └── VoiceButton.kt      # 语音按钮
│   │   └── theme/
│   ├── domain/
│   │   ├── agent/           # Agent 编排逻辑
│   │   │   ├── LiteracyAgent.kt    # Agent 主类
│   │   │   ├── SystemPrompt.kt     # 系统提示词构建
│   │   │   ├── ToolDispatcher.kt   # 工具调度
│   │   │   └── SafetyGuard.kt      # 安全护栏
│   │   ├── learning/        # 教学逻辑
│   │   │   ├── SpacedRepetition.kt # 间隔重复
│   │   │   ├── StrokeEvaluator.kt  # 笔画评估
│   │   │   └── Curriculum.kt       # 课程编排
│   │   └── model/           # 领域模型
│   ├── data/
│   │   ├── local/           # Room DAO
│   │   ├── remote/          # LLM provider adapters
│   │   └── repository/
│   └── voice/               # STT/TTS 封装
└── build.gradle.kts
```

## 待验证

- [ ] Compose Canvas 手写延迟测试（低端 Android 设备）
- [ ] Sherpa-ONNX Android 集成实际体验
- [ ] Provider 完整 JSON 响应与 TTS/工具执行串联策略
- [ ] Provider 超时、限流和鉴权失败时的提示与重试流程
- [ ] 最小 SDK 版本选择（考虑兼容性）
