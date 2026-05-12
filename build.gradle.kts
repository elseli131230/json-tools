import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.cn.else.jsontools"
version = "3.2.9"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 与 Maven 的 provided 一致：运行由 IDE 提供，避免重复打包 kotlin-stdlib。
    compileOnly(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.11.0") {
        exclude(group = "com.google.errorprone")
    }
}

intellij {
    // 编译期 SDK；兼容区间见下方 patchPluginXml（与 since/until 解耦）。
    version.set("2024.1.7")
    type.set("IC")
    updateSinceUntilBuild.set(false)
}

tasks {
    patchPluginXml {
        version.set(project.version.toString())
        // 2023.2 及更早平台缺少 ToolWindowFactory 上若干默认方法；Kotlin 2.x 对接口会生成
        // invokespecial 桥接导致 NoSuchMethodError。实际最低支持自 2023.3（233）起。
        sinceBuild.set("233")
        // 2026.1 对应平台分支 261（例如 IU-261.23567.138）；253.* 会拒绝安装。
        untilBuild.set("271.*")
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    test {
        enabled = false
    }

    buildSearchableOptions {
        enabled = false
    }
}
