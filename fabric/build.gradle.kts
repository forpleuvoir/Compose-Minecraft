@file:Suppress("UnstableApiUsage")

import java.util.*
import kotlin.random.Random
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider

plugins {
    id("multiloader-loader")
    alias(libs.plugins.fabricLoom)
}

val modId: String = project.findProperty("mod_id").toString()

repositories {
    maven {
        name = "Terraformers"
        url = uri("https://maven.terraformersmc.com/")
    }
}

/**
 * Compose 依赖的排除规则(参考 ibuki_gourd):
 * - kotlin/kotlinx 由 fabric-language-kotlin 提供,不随 mod 打包;
 * - org.jetbrains:annotations 由 Minecraft/fabric 环境提供。
 */
fun ExternalModuleDependency.composeExclude() {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(module = "annotations")
}

/**
 * 版本目录坐标转 "group:name:version" 字符串。
 *
 * KTS 中 `api(provider) { configure }` 的配置 lambda 形式会返回 Unit,
 * 无法作为 includeInternal 的参数;与 ibuki_gourd 一致改用字符串坐标。
 */
fun Provider<MinimalExternalModuleDependency>.asCoordinates(): String = get().toString()

dependencies {
    minecraft(libs.minecraft)

    //Fabric
    implementation(libs.fabricLoader)
    implementation(libs.fabricApi)

    implementation(libs.fabricKotlin)
    implementation(libs.modMenu) // ModMenu 依赖（集成代码后续再写）

    // ── Compose 平台 Mod 依赖打包(参考 ibuki_gourd 的 includeInternal(api(...)) 方式)──
    // common 模块编译依赖的 Compose Runtime/基础模块,随 mod jar 打入 META-INF/jars/。
    // 运行时由 fabric-language-kotlin 提供 kotlin/kotlinx-coroutines/atomicfu。
    //
    // ⚠ 坐标是 KMP metadata 模块,解析出的 xxx-desktop 变体(runtime-desktop 等)
    //   是 Compose Multiplatform 对 JVM 目标的官方命名,纯 JVM 字节码,
    //   不含 Skia/桌面 UI;带 Skia 的 ui-graphics-desktop/ui-desktop 不在依赖树中。
    includeInternal(api(libs.composeRuntime.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.composeRuntimeSaveable.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.composeRuntimeRetain.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.composeUiUnit.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.composeUiGeometry.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.composeUiUtil.asCoordinates()) { composeExclude() })

    includeInternal(api(libs.androidxAnnotation.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.androidxCollection.asCoordinates()) { composeExclude() })

    // kotlinx-coroutines-core / atomicfu 不随 mod 打包:运行时由 fabric-language-kotlin
    // 提供(见上方 composeExclude 注释),编译类路径由 common 的 api 依赖传递提供。
    includeInternal(api(libs.lifecycleRuntimeCompose.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.lifecycleViewmodel.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.lifecycleViewmodelSavedstate.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.savedstateCompose.asCoordinates()) { composeExclude() })
    includeInternal(api(libs.navigationevent.asCoordinates()) { composeExclude() })
}

// devOnly source set(参考 ibuki_gourd):开发/测试代码只在 dev run classpath 生效,不进发布 jar
sourceSets {
    create("devOnly") {
        val commonDevOnly = project(":common").sourceSets["devOnly"]
        compileClasspath += main.get().compileClasspath + main.get().output + commonDevOnly.compileClasspath + commonDevOnly.output
        runtimeClasspath += main.get().runtimeClasspath + main.get().output + commonDevOnly.runtimeClasspath + commonDevOnly.output
    }
}

loom {
    // AW 接线保留（文件不存在时自动跳过）
    val aw = project(":common").file("src/main/resources/${modId}.classtweaker")
    if (aw.exists()) {
        accessWidenerPath.set(aw)
    }

    runs {
        named("client") {
            client()
            displayName = "Fabric Client"
            generateRunConfig = true
            runDirectory.set(File("runs/client"))
            val name: String = System.getenv("mcName") ?: "Dev${Random.nextInt(1000)}"
            val uuid: String = System.getenv("mcUUID") ?: UUID.randomUUID().toString()
            programArguments.addAll("--username", name, "--uuid", uuid)
            sourceSet = "devOnly"
        }
        named("server") {
            server()
            displayName = "Fabric Server"
            generateRunConfig = true
            runDirectory.set(File("runs/server"))
        }
    }
}

val loaderAttribute = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
listOf("apiElements", "runtimeElements", "sourcesElements").forEach {
    configurations.named(it) {
        attributes {
            attribute(loaderAttribute, "fabric")
        }
    }
}

sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach {
        configurations.named(it) {
            attributes {
                attribute(loaderAttribute, "fabric")
            }
        }
    }
}
