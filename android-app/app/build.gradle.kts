plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.literacy.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.literacy.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 覆盖默认 debug 签名：固定 keystore（仓库内），容器构建每次一致，升级安装不冲突
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")   // android-app/keystore/
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            // release 签名：本地生成 keystore/release.keystore（随机密码，gitignore，不进仓库）。
            // review-10 P1-13：签名材料缺失时 release 构建直接失败——不产出 unsigned APK 当发布产物
            signingConfig = if (file("../keystore/release.keystore").exists()) {
                signingConfigs.create("release") {
                    // 简单 key=value 解析（避免 java.util.Properties 与 Java 插件扩展命名冲突）
                    val props = file("../keystore/release.properties").takeIf { it.exists() }
                        ?.readLines()?.mapNotNull { line ->
                            line.split("=", limit = 2).takeIf { it.size == 2 }
                        }?.associate { it[0] to it[1] } ?: emptyMap()
                    storeFile = file("../keystore/release.keystore")
                    storePassword = props["storePassword"] ?: ""
                    keyAlias = props["keyAlias"] ?: "literacy"
                    keyPassword = storePassword   // PKCS12 单密码：key 密码必须等于 store 密码
                }
            } else {
                tasks.configureEach {
                    if (name.startsWith("assemble") && name.contains("Release", ignoreCase = true)) {
                        doFirst { throw GradleException(
                            "release 签名缺失：需要 android-app/keystore/release.keystore + release.properties。\n" +
                            "生成：keytool -genkeypair -keystore android-app/keystore/release.keystore ...（密码随机，不进 git）")
                        }
                    }
                }
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 共享 agent-core domain 源码（Android 不能直接依赖 JVM 模块变体；
    // agent-core main 无 JVM 特有依赖，源码直接编译进 app）
    sourceSets["main"].apply {
        kotlin.srcDir("/agent-core/src/main/kotlin")
    }

    // Room 迁移测试：导出的 schema JSON（app/schemas）打进 androidTest assets，
    // MigrationTestHelper 按 databaseClass/version.json 读取（room-testing 依赖）
    sourceSets["androidTest"].apply {
        assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // domain 核心依赖（agent-core 同款；sqlite-jdbc 仅测试用，不入 app）
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")

    // Room（学习数据持久化：characters/sessions/name_plan）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // api_key 加密存储（EncryptedSharedPreferences；1.1.0 后无稳定版但功能稳定，minSdk 26 兼容）
    implementation("androidx.security:security-crypto:1.1.0-alpha07")

    // ---- androidTest（instrumented；App 层零测试空白）----
    androidTestImplementation("androidx.room:room-testing:2.6.1")   // MigrationTestHelper
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")         // AndroidJUnitRunner
    androidTestImplementation("androidx.test:rules:1.5.0")
    // Compose UI 测试（BOM 版本与 main 对齐）
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")     // createAndroidComposeRule 需要
}
