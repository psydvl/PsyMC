import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withGroovyBuilder
import psymc.configureClientSmoke
import psymc.configureServerSmokeLeaf
import psymc.javaLanguage
import psymc.libraryJars
import psymc.modPinById
import psymc.requiredProperty
import psymc.selectedTarget
import java.io.File

// Run the built Fabric pin jar on one named release (26.1 and later).
// The game jar is not obfuscated. The jars go into mods/ without a remap.

plugins {
    `java-library`
    id("net.fabricmc.fabric-loom")
}

val selected = selectedTarget()
val pinSpec = modPinById(selected.pin)

version = "0.0.0"
group = "dev.psydvl.mc.smoke"

javaLanguage(selected.javaVersion)

repositories {
    mavenCentral()
    maven {
        name = "Modrinth"
        url = uri("https://api.modrinth.com/maven")
        content {
            includeGroup("maven.modrinth")
        }
    }
}

val pinJar = File(rootProject.projectDir, "build/version-smoke/jars/${selected.pin}-fabric.jar")
if (!pinJar.isFile) {
    throw GradleException("Build the pin jar before this launch: $pinJar")
}

dependencies {
    add("minecraft", "com.mojang:minecraft:${selected.minecraftVersion}")
    add("compileOnly", "net.fabricmc:fabric-loader:${requiredProperty("fabric_loader_version")}")
    add("runtimeOnly", "net.fabricmc:fabric-loader:${requiredProperty("fabric_loader_version")}")
    add("runtimeOnly", "net.fabricmc.fabric-api:fabric-api:${selected.fabricApi(pinSpec)}")
}

apply(from = layout.settingsDirectory.file("gradle/smoke.gradle.kts").asFile.path)

extensions.getByName("loom").withGroovyBuilder {
    "runs" {
        "client" {
            "runDir"(layout.buildDirectory.dir("client").get().asFile.absolutePath)
        }
        "server" {
            "runDir"(layout.buildDirectory.dir("server").get().asFile.absolutePath)
            "programArg"("--nogui")
        }
    }
}

tasks.named<JavaExec>("runClient").configure {
    configureClientSmoke(this, layout.buildDirectory.dir("client").get().asFile, pinJar)
}

tasks.named<JavaExec>("runServer").configure {
    configureServerSmokeLeaf(this, layout.buildDirectory.dir("server").get().asFile, pinJar, libraryJars())
}
