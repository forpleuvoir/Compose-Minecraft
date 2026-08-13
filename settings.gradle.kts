pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        //Fabric
        exclusiveContent {
            forRepository {
                maven {
                    name = "Fabric"
                    url = uri("https://maven.fabricmc.net/")
                }
            }
            filter {
                includeGroupByRegex("net\\.fabricmc.*")
            }
        }
        exclusiveContent {
            forRepository {
                maven {
                    name = "Modrinth"
                    url = uri("https://api.modrinth.com/maven")
                }
            }
            filter {
                includeGroupByRegex("maven\\.modrinth")
            }
        }
        maven { url = uri("https://www.jitpack.io") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}

rootProject.name = "Compose-Minecraft"

include("common")
include("fabric")
include("neoforge")
