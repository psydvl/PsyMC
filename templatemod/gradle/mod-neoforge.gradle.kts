import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.named
import org.gradle.language.jvm.tasks.ProcessResources
import psymc.configurationName
import psymc.configureHeadlessClient
import psymc.configureServerSmokeClient
import psymc.configureServerSmokeServer
import psymc.configureSmokeStop
import psymc.configureTestWorld
import psymc.hideNeoForgeLoadWarnings
import psymc.modPin
import psymc.neoForge
import psymc.neoForgeClientRun
import psymc.requiredProperty
import psymc.serverSmokePort
import psymc.sourceSets
import psymc.writeServerFiles

// NeoForge pin leaf. The leaf build file applies ModDevGradle.

apply(from = layout.settingsDirectory.file("gradle/mod-common.gradle.kts").asFile.path)

val pin = modPin()
val modId = requiredProperty("mod_id")

dependencies {
    pin.libraries.forEach { library ->
        add("implementation", "maven.modrinth:${library.slug}:${library.neoforge}") { isTransitive = false }
    }
}

val mainSource = sourceSets().getByName("main")
val port = serverSmokePort("neoforge")
neoForge {
    setProperty("version", pin.neo)
    // An access transformer goes here:
    // getProperty("accessTransformers").withGroovyBuilder { "from"(file(...)) }
    "mods" {
        "create"(modId) {
            "sourceSet"(mainSource)
        }
    }
    "runs" {
        "create"("client") {
            "client"()
            setProperty("devLogin", false)
            if (project.hasProperty("serverSmoke")) {
                setProperty("gameDirectory", layout.buildDirectory.dir("server-smoke-client"))
                "programArgument"("--quickPlayMultiplayer")
                "programArgument"("localhost:$port")
            } else {
                setProperty("gameDirectory", layout.buildDirectory.dir("client"))
            }
        }
        "create"("server") {
            "server"()
            "programArgument"("--nogui")
            setProperty("gameDirectory", layout.buildDirectory.dir("server"))
        }
    }
}

apply(from = layout.settingsDirectory.file("gradle/smoke.gradle.kts").asFile.path)

gradle.projectsEvaluated {
    val serverDir = layout.buildDirectory.dir("server").get().asFile
    val clientDir = layout.buildDirectory.dir("client").get().asFile
    val smokeClientDir = layout.buildDirectory.dir("server-smoke-client").get().asFile

    // Transformed GLFW calls the HeadlessMc agent. Put the agent jar on the game classpath.
    fun addHeadlessLwjgl() {
        val lwjgl = files(configurations.getByName("headlessLwjgl").singleFile)
        val lwjglConfig = if (pin.lwjglRuntime == "additional") {
            neoForgeClientRun().configurationName("additionalRuntimeClasspathConfiguration")
        } else {
            "runtimeOnly"
        }
        dependencies.add(lwjglConfig, lwjgl)
    }

    tasks.named<JavaExec>("runServer").configure {
        if (project.hasProperty("serverSmoke")) {
            configureServerSmokeServer(this, serverDir, port)
        } else {
            doFirst {
                writeServerFiles(serverDir, port, false)
            }
            // -Pplay keeps the server running for a person. The smoke run stops when it is up.
            if (!project.hasProperty("play")) {
                configureSmokeStop(this, serverDir, "For help, type \"help\"")
            }
        }
    }
    tasks.named<JavaExec>("runClient").configure {
        if (project.hasProperty("serverSmoke")) {
            addHeadlessLwjgl()
            workingDir = smokeClientDir
            configureServerSmokeClient(this, smokeClientDir)
            hideNeoForgeLoadWarnings(this, smokeClientDir)
        } else if (!project.hasProperty("play")) {
            // -Pplay keeps the window open for a person. The smoke run stays headless.
            addHeadlessLwjgl()
            configureHeadlessClient(this)
            configureSmokeStop(this, clientDir, "Sound engine started", listOf("Error starting SoundSystem"))
        } else {
            configureTestWorld(this, clientDir)
        }
    }
}

val metadata = mapOf(
    "mod_id" to modId,
    "mod_name" to requiredProperty("mod_name"),
    "mod_version" to requiredProperty("mod_version"),
    "mod_description" to requiredProperty("mod_description"),
    "mod_authors" to requiredProperty("mod_authors"),
    "mod_license" to requiredProperty("mod_license"),
    "issue_tracker_url" to requiredProperty("issue_tracker_url"),
    "minecraft_version_range" to pin.minecraftRange,
    "neo_version_range" to pin.neoRange,
    "loader_version_range" to pin.loaderRange,
)

tasks.named<ProcessResources>("processResources") {
    inputs.properties(metadata)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(metadata)
    }
}
