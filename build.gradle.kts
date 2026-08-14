plugins {
    alias(libs.plugins.fabricLoom).apply(false)
    alias(libs.plugins.neoforgedModDev).apply(false)
}

// 所有 Jar(含发布 Jar)携带 LICENSE / NOTICE(Apache 2.0 第 4 条要求)
subprojects {
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        from(rootProject.file("NOTICE")) {
            into("META-INF")
        }
    }
}

tasks {
    register<Copy>("buildAllModJar") {
        description = "构建 fabric + neoforge 的模组 Jar"
        dependsOn(":fabric:jar", ":neoforge:jar")
        val minecraftVersion = libs.versions.minecraft.get()
        doFirst {
            val outputDir = File(project.rootDir, "modJar/$minecraftVersion/$version")
            outputDir.mkdirs()
        }
        from(project(":fabric").tasks.named<AbstractArchiveTask>("jar").get().archiveFile) {
            rename { "${project.name}-fabric-$version-minecraft.$minecraftVersion.jar" }
        }
        from(project(":neoforge").tasks.named<AbstractArchiveTask>("jar").get().archiveFile) {
            rename { "${project.name}-neoforge-$version-minecraft.$minecraftVersion.jar" }
        }
        into(file("modJar/$minecraftVersion/$version"))
    }
}
