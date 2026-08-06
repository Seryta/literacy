plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20240303")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // 测试读取容器挂载的外部目录（Makefile test 目标挂载到 /test-cases /fixtures /data，
    // 测试内以 ../xxx 相对工作目录引用）——声明为 inputs：内容变化触发重跑，
    // 消除改 fixture/用例/字库后 test 仍 UP-TO-DATE 的假绿。
    inputs.dir(projectDir.resolve("../test-cases"))
    // fixtures 同时是 RecordFixturesTest（需 DEEPSEEK_API_KEY，按需运行不进 CI）的产物目录——
    // 录制写入新 fixture 后 test 重跑是期望行为，无需排除。
    inputs.dir(projectDir.resolve("../fixtures"))
    // data/ 只读 hanzi.db（build_hanzi_db.py / NOTICES 是构建期文件，不影响测试结果）——
    // 收窄为单文件，改构建脚本不再误触发重跑（review 反馈 Suggestion 3）。
    inputs.file(projectDir.resolve("../data/hanzi.db"))
}
