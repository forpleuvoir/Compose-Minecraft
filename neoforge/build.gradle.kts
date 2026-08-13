import org.gradle.internal.extensions.stdlib.capitalized
import java.util.UUID
import kotlin.random.Random

plugins {
    id("multiloader-loader")
    alias(libs.plugins.neoforgedModDev)
}

val modId: String = project.findProperty("mod_id").toString()

dependencies {
    // Kotlin for Forge：neoforge.mods.toml 中 modLoader = "kotlinforforge"
    implementation(libs.forgeKotlin)
}

neoForge {
    version = libs.versions.neoforge.get()

    // AT 接线保留（文件不存在时自动跳过）
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

val loaderAttribute = Attribute.of("io.github.mcgradleconventions.loader", String::class.java)
listOf("apiElements", "runtimeElements", "sourcesElements").forEach { configurationName ->
    configurations.named(configurationName) {
        attributes {
            attribute(loaderAttribute, "neoforge")
        }
    }
}

sourceSets.configureEach {
    listOf(compileClasspathConfigurationName, runtimeClasspathConfigurationName).forEach { configurationName ->
        configurations.named(configurationName) {
            attributes {
                attribute(loaderAttribute, "neoforge")
            }
        }
    }
}
