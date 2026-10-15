import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withGroovyBuilder
import psymc.classpathModJars
import psymc.configureClientSmoke
import psymc.configureServerSmokeLeaf
import psymc.javaLanguage
import psymc.modPinById
import psymc.mojangMappings
import psymc.requiredProperty
import psymc.selectedTarget
import java.io.File

// Run the built Fabric pin jar on one obfuscated release (up to 1.21.11).
// Loom remaps the pin jar, the libraries, and the -Pcompat mods for this release.

plugins {
    `java-library`
    id("net.fabricmc.fabric-loom-remap")
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
    add("mappings", mojangMappings())
    add("modImplementation", "net.fabricmc:fabric-loader:${requiredProperty("fabric_loader_version")}")
    add("modImplementation", files(pinJar))
    add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${selected.fabricApi(pinSpec)}")
    // See classpathModJars.
    add("modLocalRuntime", files(classpathModJars()))
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
    configureClientSmoke(this, layout.buildDirectory.dir("client").get().asFile, null, modsOnClasspath = true)
}

tasks.named<JavaExec>("runServer").configure {
    configureServerSmokeLeaf(this, layout.buildDirectory.dir("server").get().asFile, null, emptyList())
}
