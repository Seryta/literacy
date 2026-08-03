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
data/         字库管线（makemeahanzi → SQLite）
test-cases/   golden turn 测试用例集（53 个）
fixtures/     真实 LLM 录制/回放（deepseek）
docker/       Android SDK 构建镜像
```

- 测试：JVM 110 + Android instrumented 13 + fixture 回放
- 构建：全部在容器中（`make test` / `make build` / `make check`）
- 隐私：不向模型传真实姓名、用户话语不落日志、API Key 加密存储

## 构建

```bash
make test           # JVM 测试（容器）
make build          # Android APK
make check          # 推送前完整检查（pii + test + build + lint + androidTest）
make record         # 录制真实 LLM 输出（需 DEEPSEEK_API_KEY）
```

详见 [agent-core/README.md](agent-core/README.md)。

## 许可

[MIT License](LICENSE)
