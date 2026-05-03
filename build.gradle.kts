import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.cn.else.jsontools"
version = "3.2.6"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
        // since-build 仍从 2018.1 段起声明；until-build 用 999.* 表示不设实际上限（未来新 IDEA 构建号仍属可安装范围）。
        sinceBuild.set("181")
        untilBuild.set("999.*")
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
