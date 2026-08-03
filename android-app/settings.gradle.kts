// Android App 工程：app（Compose UI）+ agent-core（domain 核心，../agent-core）
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "literacy-android"

include(":app")
