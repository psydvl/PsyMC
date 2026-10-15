import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import psymc.MinecraftRelease
import psymc.configureClientSmoke
import psymc.configureServerSmokeLeaf
import psymc.requiredProperty
import psymc.selectedTarget
import java.io.File

// Run the built nil jar on one release of the vanilla game. NilLoader is the only loader.
// The client and the server come from the launcher metadata.

plugins {
    `java-base`
}

val selected = selectedTarget()
val release = MinecraftRelease(gradle.gradleUserHomeDir, selected.minecraftVersion)

repositories {
    mavenCentral()
    maven {
        name = "Sleeping Town"
        url = uri("https://repo.sleeping.town")
        content {
            includeGroup("com.unascribed")
        }
    }
}

val pinJar = File(rootProject.projectDir, "build/version-smoke/jars/${selected.pin}-nil.jar")
if (!pinJar.isFile) {
    throw GradleException("Build the pin jar before this launch: $pinJar")
}

val nilLoader = configurations.create("nilLoader") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies.add("nilLoader", "com.unascribed:nilloader:${requiredProperty("nilloader_version")}")

apply(from = layout.settingsDirectory.file("gradle/smoke.gradle.kts").asFile.path)

val clientDir = layout.buildDirectory.dir("client").get().asFile

tasks.register<JavaExec>("runClient") {
    group = "verification"
    description = "Start the vanilla client with NilLoader and the nil jar."
    javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(selected.javaVersion))
        },
    )
    workingDir = clientDir
    mainClass.set(release.mainClass)
    classpath = files(release.runtimeLibraries() + release.clientJar)
    // NilLoader finds the nilmods in mods/ of the working directory.
    jvmArgs("-javaagent:${nilLoader.singleFile.absolutePath}")
    args(
        "--version", release.version,
        "--gameDir", clientDir.absolutePath,
        "--assetsDir", release.assetsDir().absolutePath,
        "--assetIndex", release.assetIndex,
        "--username", "Dev",
        "--accessToken", "0",
        "--userType", "legacy",
        "--versionType", "release",
    )
    doFirst {
        clientDir.mkdirs()
    }
    configureClientSmoke(this, clientDir, pinJar)
}

val serverDir = layout.buildDirectory.dir("server").get().asFile

tasks.register<JavaExec>("runServer") {
    group = "verification"
    description = "Start the vanilla dedicated server with NilLoader and the nil jar."
    javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(selected.javaVersion))
        },
    )
    workingDir = serverDir
    // The jars out of the server bundler: the game classes in the system class loader, as on the client.
    val (serverJars, serverMain) = release.serverClasspath
    mainClass.set(serverMain)
    classpath = files(serverJars)
    // NilLoader finds the nilmods in mods/ of the working directory.
    jvmArgs("-javaagent:${nilLoader.singleFile.absolutePath}")
    args("nogui")
    configureServerSmokeLeaf(this, serverDir, pinJar, emptyList())
}
