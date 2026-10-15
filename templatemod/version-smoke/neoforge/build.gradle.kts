import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.named
import psymc.configurationName
import psymc.configureClientSmoke
import psymc.configureServerSmokeLeaf
import psymc.javaLanguage
import psymc.libraryJars
import psymc.neoForge
import psymc.neoForgeClientRun
import psymc.selectedTarget
import java.io.File

// Run the built NeoForge pin jar on one release. The root smoke tasks start this launch.

plugins {
    `java-library`
    id("net.neoforged.moddev")
}

val selected = selectedTarget()

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

val pinJar = File(rootProject.projectDir, "build/version-smoke/jars/${selected.pin}-neoforge.jar")
if (!pinJar.isFile) {
    throw GradleException("Build the pin jar before this launch: $pinJar")
}

neoForge {
    setProperty("version", selected.properties.getValue("neoforge_loader"))
    "runs" {
        "create"("client") {
            "client"()
            setProperty("gameDirectory", layout.buildDirectory.dir("client"))
        }
        "create"("server") {
            "server"()
            setProperty("gameDirectory", layout.buildDirectory.dir("server"))
            "programArgument"("--nogui")
        }
    }
}

apply(from = layout.settingsDirectory.file("gradle/smoke.gradle.kts").asFile.path)

// Fabric puts HeadlessMc on the game classpath with fabric.systemLibraries.
// NeoForge needs the jar on the run classpath, or the agent class is missing.
val headless = files(configurations.getByName("headlessLwjgl").singleFile)
try {
    dependencies.add(neoForgeClientRun().configurationName("additionalRuntimeClasspathConfiguration"), headless)
} catch (_: RuntimeException) {
    dependencies.add("runtimeOnly", headless)
}

tasks.named<JavaExec>("runClient").configure {
    configureClientSmoke(this, layout.buildDirectory.dir("client").get().asFile, pinJar)
}

tasks.named<JavaExec>("runServer").configure {
    configureServerSmokeLeaf(this, layout.buildDirectory.dir("server").get().asFile, pinJar, libraryJars())
}
