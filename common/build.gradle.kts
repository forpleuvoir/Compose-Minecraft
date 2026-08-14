plugins {
    id("multiloader-common")
    alias(libs.plugins.neoforgedModDev)
}

dependencies {
    // ── Compose Runtime / 平台中立基础模块(官方 artifact,不携带 Skia)──
    // Compose UI commonMain 源码(仓库内 androidx/compose)依赖这些已编译模块;
    // ui-graphics/ui-text/ui/foundation/animation 的 commonMain 与 Minecraft actual 在同一源码树内编译。
    // 坐标与 org.jetbrains.compose.ui:ui:1.11.0 的 Gradle metadata 保持一致;
    // androidx.compose.* 实现在 Google Maven(google() 已在 multiloader-common 配置)。
    //
    // ⚠ 关于 "desktop" 后缀:
    //   这里的坐标(org.jetbrains.compose.runtime:runtime 等)是 KMP metadata 模块,
    //   Gradle 在 JVM 目标上解析时自动选中 xxx-desktop 变体(runtime-desktop 等)。
    //   "-desktop" 是 Compose Multiplatform 对 JVM 目标的官方 artifact 命名
    //   (类似 Android 目标叫 -android),解析出的 JAR 是纯 JVM 字节码,
    //   不含任何 Skia/Skiko 代码,也不是"桌面 UI 平台"。
    //   真正携带 Skia 的 ui-graphics-desktop / ui-desktop 本模块【不依赖】——
    //   graphics/text 的渲染层由仓库内 MinecraftCanvas / MinecraftParagraph 自实现。
    api(libs.composeRuntime)
    api(libs.composeRuntimeSaveable)
    api(libs.composeRuntimeRetain)
    api(libs.composeUiUnit)
    api(libs.composeUiGeometry)
    api(libs.composeUiUtil)

    api(libs.androidxAnnotation)
    api(libs.androidxCollection)
    api(libs.kotlinxCoroutinesCore)
    api(libs.atomicfu)

    api(libs.lifecycleRuntimeCompose)
    api(libs.lifecycleViewmodel)
    api(libs.lifecycleViewmodelSavedstate)
    api(libs.savedstateCompose)

    // scene 移植(PlatformArchitectureComponentsOwner 等)依赖的导航事件库
    api(libs.navigationevent)
}

// 仅用 neoForm 反编译 Minecraft 供 common 编译；AT 接线保留（文件不存在时自动跳过）
neoForge {
    neoFormVersion = libs.versions.neoForm.get()
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) {
        accessTransformers.from(at.absolutePath)
    }
}

// devOnly source set(参考 ibuki_gourd):开发/测试代码(如开发验证 Scene)
// 只在 dev run classpath 生效,不进入发布 jar。
sourceSets {
    create("devOnly") {
        compileClasspath += main.get().compileClasspath + main.get().output
    }
}

// common 源码/资源输出配置：loader 模块通过 commonJava/commonKotlin/commonResources 消费
configurations {
    create("commonJava") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonKotlin") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
    create("commonResources") {
        isCanBeResolved = false
        isCanBeConsumed = true
    }
}

artifacts {
    add("commonJava", sourceSets.main.get().java.sourceDirectories.singleFile)
    add("commonKotlin", sourceSets.main.get().kotlin.sourceDirectories.filter { !it.name.endsWith("java") }.singleFile)
    add("commonResources", file("src/main/resources"))
}

val loaderAttribute = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
listOf("apiElements", "runtimeElements", "sourcesElements").forEach {
    configurations.named(it) {
        attributes {
            attribute(loaderAttribute, "common")
        }
    }
}

sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach {
        configurations.named(it) {
            attributes {
                attribute(loaderAttribute, "common")
            }
        }
    }
}
