import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.provider.Provider
import org.gradle.internal.extensions.stdlib.capitalized
import java.util.UUID
import kotlin.random.Random

plugins {
    id("multiloader-loader")
    alias(libs.plugins.neoforgedModDev)
}

val modId: String = project.findProperty("mod_id").toString()

/*
 * 需要同时：
 *
 * 1. 暴露到 api。
 * 2. 连同传递依赖一起打入 JarJar。
 *
 * 的依赖统一声明在这里。
 */
val bundledApi = configurations.create("bundledApi") {
    isCanBeResolved = false
    isCanBeConsumed = false
}

/*
 * 专门解析 bundledApi 的完整传递依赖树。
 *
 * NeoForge 的 jarJar 默认只嵌入直接依赖，因此不能简单地让
 * jarJar extendsFrom bundledApi。
 */
val jarJarInternal = configurations.create("jarJarInternal") {
    isCanBeResolved = true
    isCanBeConsumed = false

    extendsFrom(bundledApi)

    /*
     * 配置级排除（与 composeExclude 语义一致）：
     *
     * kotlin-stdlib 由 Kotlin for Forge 提供；org.jetbrains:annotations
     * 由 Minecraft 环境提供。依赖级排除在 replacedBy 重定向后的
     * androidx.* 传递子树中不总是生效（如 lifecycle 2.9.x 依赖
     * kotlin-stdlib），因此在这里兜底。
     *
     * 注意不能排除 org.jetbrains.kotlinx 组：atomicfu 自身属于该组
     * 且必须随 mod 打包（forgeKotlin 不提供）。
     */
    exclude(group = "org.jetbrains.kotlin")
    exclude(module = "annotations")

    attributes {
        attribute(
            Usage.USAGE_ATTRIBUTE,
            project.objects.named(Usage.JAVA_RUNTIME)
        )
        attribute(
            LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            project.objects.named(LibraryElements.JAR)
        )
        attribute(
            Category.CATEGORY_ATTRIBUTE,
            project.objects.named(Category.LIBRARY)
        )
        attribute(
            Bundling.BUNDLING_ATTRIBUTE,
            project.objects.named(Bundling.EXTERNAL)
        )
    }
}

/*
 * bundledApi 中声明的依赖同时对当前模块的 api 可见。
 *
 * 这样就不需要再写：
 *
 * jarJarInternal(api(...))
 */
configurations.named("api") {
    extendsFrom(bundledApi)
}

fun ExternalModuleDependency.composeExclude() {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(module = "annotations")
}

/**
 * 版本目录坐标转 "group:name:version" 字符串。
 *
 * KTS 中 `api(provider) { configure }` 的配置 lambda 形式会返回 Unit,
 * 无法作为依赖声明使用;与 ibuki_gourd 一致改用字符串坐标。
 */
fun Provider<MinimalExternalModuleDependency>.asCoordinates(): String = get().toString()

/*
 * Compose Multiplatform 最近迁移过部分依赖坐标。
 *
 * org.jetbrains.* 下的旧模块通常只是几 KB 的重定向空壳，
 * 不能和实际的 androidx.* JAR 一起交给 NeoForge JarJar。
 *
 * ⚠ 涉及的 xxx-desktop 是 CMP 对 JVM 目标的官方 artifact 命名
 *   (纯 JVM 字节码,不含 Skia/桌面 UI),并非"桌面平台"依赖;
 *   带 Skia 的 ui-graphics-desktop/ui-desktop 不在依赖树中。
 */
val composeModuleReplacements = mapOf(
    "org.jetbrains.compose.runtime:runtime-desktop" to
            "androidx.compose.runtime:runtime-desktop",

    "org.jetbrains.compose.runtime:runtime-saveable-desktop" to
            "androidx.compose.runtime:runtime-saveable-desktop",

    "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop" to
            "androidx.lifecycle:lifecycle-runtime-compose-desktop",

    "org.jetbrains.androidx.savedstate:savedstate-compose-desktop" to
            "androidx.savedstate:savedstate-compose-desktop"
)

dependencies {
    // Kotlin for Forge：neoforge.mods.toml 中 modLoader = "kotlinforforge"
    implementation(libs.forgeKotlin)

    /*
     * 当新旧坐标同时出现在依赖树中时，选择实际的 androidx 实现，
     * 不再保留旧的重定向模块。
     */
    modules {
        composeModuleReplacements.forEach { (oldModule, replacementModule) ->
            module(oldModule) {
                replacedBy(
                    replacementModule,
                    "Compose Multiplatform artifact relocation"
                )
            }
        }
    }

    /*
     * 所有需要：
     *
     * 1. 暴露给主源码；
     * 2. 展开全部传递依赖；
     * 3. 嵌入最终 NeoForge JAR；
     *
     * 的依赖都声明到 bundledApi。
     */
    bundledApi(libs.composeRuntime.asCoordinates()) { composeExclude() }
    bundledApi(libs.composeRuntimeSaveable.asCoordinates()) { composeExclude() }
    bundledApi(libs.composeRuntimeRetain.asCoordinates()) { composeExclude() }
    bundledApi(libs.composeUiUnit.asCoordinates()) { composeExclude() }
    bundledApi(libs.composeUiGeometry.asCoordinates()) { composeExclude() }
    bundledApi(libs.composeUiUtil.asCoordinates()) { composeExclude() }

    bundledApi(libs.androidxAnnotation.asCoordinates()) { composeExclude() }
    bundledApi(libs.androidxCollection.asCoordinates()) { composeExclude() }

    /*
     * atomicfu 需要随 mod 打包，但 Kotlin for Forge 没有提供。
     * coroutines 由 forgeKotlin 提供，随 composeExclude 排除。
     */
    bundledApi(libs.atomicfu.asCoordinates()) { composeExclude() }

    bundledApi(libs.lifecycleRuntimeCompose.asCoordinates()) { composeExclude() }
    bundledApi(libs.lifecycleViewmodel.asCoordinates()) { composeExclude() }
    bundledApi(libs.lifecycleViewmodelSavedstate.asCoordinates()) { composeExclude() }
    bundledApi(libs.savedstateCompose.asCoordinates()) { composeExclude() }

    // scene 移植(PlatformArchitectureComponentsOwner 等)依赖的导航事件库
    bundledApi(libs.navigationevent.asCoordinates()) { composeExclude() }
}

/*
 * 模拟 Loom includeInternal：
 *
 * 1. 解析 jarJarInternal 的完整传递依赖树。
 * 2. 将每个解析完成的 Maven 模块提升为 jarJar 直接依赖。
 * 3. 将这些依赖设为非传递，避免 jarJar 再解析一次依赖树。
 *
 * 不能使用 resolvedConfiguration.lenientConfiguration：
 *
 * - 它是旧 API；
 * - 会丢失解析后的 variant 信息；
 * - lenient 模式可能静默忽略打包依赖错误。
 */
configurations.named("jarJar") {
    dependencies.addAllLater(
        project.provider {
            val resolvedCoordinates = jarJarInternal.incoming.artifactView {
                attributes {
                    attribute(
                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                        ArtifactTypeDefinition.JAR_TYPE
                    )
                }
            }.artifacts.resolvedArtifacts.get()
                .map { artifact ->
                    val component = artifact.id.componentIdentifier

                    val id = component as? ModuleComponentIdentifier
                        ?: error(
                            "jarJarInternal 只支持外部 Maven 模块，" +
                                    "但发现了非模块依赖：${artifact.file.absolutePath}"
                        )

                    Triple(
                        id.group,
                        id.module,
                        id.version
                    )
                }
                .distinct()

            /*
             * 额外保护：
             *
             * 即使某个 Gradle/Compose 版本没有正确应用 replacedBy，
             * 也不允许把旧重定向 JAR 放入最终产物。
             */
            val resolvedModules = resolvedCoordinates
                .mapTo(hashSetOf()) { (group, module, _) ->
                    "$group:$module"
                }

            composeModuleReplacements.forEach { (oldModule, replacementModule) ->
                check(
                    oldModule !in resolvedModules ||
                            replacementModule in resolvedModules
                ) {
                    buildString {
                        append("发现 Compose 重定向模块 ")
                        append(oldModule)
                        append("，但没有解析到对应实现 ")
                        append(replacementModule)
                    }
                }
            }

            resolvedCoordinates
                .filterNot { (group, module, _) ->
                    "$group:$module" in composeModuleReplacements
                }
                .sortedBy { (group, module, version) ->
                    "$group:$module:$version"
                }
                .map { (group, module, version) ->
                    (
                            project.dependencies.create(
                                "$group:$module:$version"
                            ) as ExternalModuleDependency
                            ).apply {
                            /*
                             * 这份依赖列表已经是展开完成后的结果。
                             *
                             * 必须关闭传递解析，否则 jarJar 会重新解析，
                             * 重新引入重定向模块或改变已选中的版本。
                             */
                            isTransitive = false

                            because(
                                "Resolved and flattened from jarJarInternal"
                            )
                        }
                }
        }
    )
}

// NeoForge 端不使用 devOnly 源码集(ModDevGradle 不支持该模式),
// dev 测试代码与验证统一在 Fabric 端进行(与 ibuki_gourd 实践一致)。

neoForge {
    version = libs.versions.neoforge.get()

    // AT 接线保留(文件不存在时自动跳过)
    val accessTransformer = project(":common")
        .file("src/main/resources/META-INF/accesstransformer.cfg")
    if (accessTransformer.exists()) {
        accessTransformers.from(accessTransformer)
    }

    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            ideName = "NeoForge ${name.capitalized()} (${project().path})"
        }

        register("client") {
            client()
            val playerName = System.getenv("mcName") ?: "Dev${Random.nextInt(1000)}"
            val playerUuid = System.getenv("mcUUID") ?: UUID.randomUUID().toString()
            programArguments.addAll("--username", playerName, "--uuid", playerUuid)
            gameDirectory = file("runs/client")
        }

        register("data") {
            clientData()
            gameDirectory = file("runs/data")
            programArguments.addAll(
                "--mod", modId, "--all", "--output",
                file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath
            )
        }

        register("server") {
            server()
            gameDirectory = file("runs/server")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets.main.get().resources {
    srcDir("src/generated/resources")
}

val loaderAttribute = Attribute.of(
    "io.github.mcgradleconventions.loader",
    String::class.java
)


listOf(
    "apiElements",
    "runtimeElements",
    "sourcesElements"
).forEach { configurationName ->
    configurations.named(configurationName) {
        attributes {
            attribute(loaderAttribute, "neoforge")
        }
    }
}

sourceSets.configureEach {
    listOf(
        compileClasspathConfigurationName,
        runtimeClasspathConfigurationName,
        getTaskName(null, "jarJar")
    ).forEach { configurationName ->
        configurations.named(configurationName) {
            attributes {
                attribute(loaderAttribute, "neoforge")
            }
        }
    }
}
