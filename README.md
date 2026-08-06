# 识字助手（Literacy）

面向需要识字的成年人，帮助他们学会读写自己的名字和常用字。

这个项目最初是为我母亲做的，也开放给所有有同样需要的家庭。

## 特点

- **从名字开始**：建档时先学自己的名字，姓名拆解成字包
- **生活字包**：家、国、爱、好、学、天等常用字
- **完整学习闭环**：认读 → 跟写 → 独立写 → 造句 → 复习（9 阶段，识读/书写/理解/应用 4 维度）
- **本地裁决**：掌握度、笔画对错、复习排期由本地规则引擎判断，AI 只负责教学对话和出题，不参与成绩判定
- **间隔重复**：按遗忘曲线安排复习，最弱维度优先
- **手写评估**：米字格手写，按起笔/收笔/方向/长度评估
- **真实字库**：9574 个汉字，含拼音、结构拆解、笔顺（makemeahanzi 数据）

## 技术架构

```
agent-core/   纯 Kotlin JVM：本地裁决引擎（阶段机/掌握裁决/间隔重复/笔画评估/意图解析）
android-app/  Android Compose App：学习界面 + Room + TTS + 加密设置
config/       运行配置样例（LLM provider 配置，复制为 provider-config.json 后生效）
data/         字库管线（makemeahanzi → SQLite）
docker/       Android SDK 构建镜像
docs/         设计/协议/教学/研究文档 + 历次评审记录
fixtures/     真实 LLM 录制/回放（deepseek）
scripts/      一键部署脚本（容器构建 APK + adb 安装）
test-cases/   golden turn 测试用例集（53 个）
```

- 测试：JVM 110 + Android instrumented 13 + fixture 回放
- 构建：全部在容器中（`make test` / `make build` / `make check`）
- 隐私：不向模型传真实姓名、用户话语不落日志、API Key 加密存储
- 大文件：`*.db`（字库）已用 Git LFS 管理，首次 clone 后请 `git lfs pull`

## 构建

```bash
make test           # JVM 测试（容器）
make build          # Android APK
make check          # 推送前完整检查（pii + test + build + lint + androidTest）
make record         # 录制真实 LLM 输出（需 DEEPSEEK_API_KEY + config/provider-config.json）
```

详见 [agent-core/README.md](agent-core/README.md)。

## 文档索引

设计与协议类文档统一归档在 `docs/`，按主题分组：

### 总览与架构
- [docs/DESIGN.md](docs/DESIGN.md) — 总设计：系统边界、模块划分、选型、教学流程、尚待调研清单
- [docs/SYSTEM-PROMPT.md](docs/SYSTEM-PROMPT.md) — 模型 System Prompt：行为约束、工具使用、上下文注入、安全边界

### 教学与课程
- [docs/TEACHING-STRATEGY.md](docs/TEACHING-STRATEGY.md) — 教学策略：优先级机制、降难矩阵、3 条学习路径、脚手架撤除、姓名拆字
- [docs/CURRICULUM-DESIGN.md](docs/CURRICULUM-DESIGN.md) — 课程设计：10 个生活字包、姓名 P0 字包、每 session 10–15 分钟
- [docs/MASTERY-CRITERIA.md](docs/MASTERY-CRITERIA.md) — 掌握达标：识读/书写/理解/应用 4 维度 0–4 级、本地裁决规则、签字达标

### 运行与数据
- [docs/AGENT-PROTOCOL.md](docs/AGENT-PROTOCOL.md) — Agent 协议：事件 → LLM turn → 工具执行 → 状态回写的闭环、阶段迁移、幂等规则
- [docs/SESSION-LIFECYCLE.md](docs/SESSION-LIFECYCLE.md) — Session 生命周期：today_brief、复习队列、疲劳/暂停、跨日恢复
- [docs/STORAGE-DESIGN.md](docs/STORAGE-DESIGN.md) — 存储设计：characters / session / profile schema、目录组织映射

### 研究与选型
- [docs/RESEARCH-TEACHING.md](docs/RESEARCH-TEACHING.md) — 成人识字教学法调研
- [docs/RESEARCH-EXERCISES.md](docs/RESEARCH-EXERCISES.md) — 练习形态：12 种 + 3 个新增工具、形近字混淆触发规则
- [docs/RESEARCH-VOICE.md](docs/RESEARCH-VOICE.md) — 语音栈选型：STT/TTS、体验标准、失败降级
- [docs/RESEARCH-TECH.md](docs/RESEARCH-TECH.md) — 技术调研：手写识别对比、字形数据、端侧推理
- [docs/RESEARCH-THEORIES.md](docs/RESEARCH-THEORIES.md) — 学习理论：支架式/提取练习/交叉练习/间隔重复、用户研究

### UI / 评审 / 法律
- [design/UI-UX-SPEC.md](design/UI-UX-SPEC.md) — UI/UX 设计契约：所有页面与交互的硬性规范
- [docs/reviews/README.md](docs/reviews/README.md) — 历次代码与设计评审记录（review-01…review-10）
- [docs/THIRD-PARTY-NOTICES.md](docs/THIRD-PARTY-NOTICES.md) — 第三方数据与开源许可声明（字库、字体）

## 许可

[MIT License](LICENSE)
