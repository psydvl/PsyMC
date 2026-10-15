import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.named
import org.gradle.language.jvm.tasks.ProcessResources
import psymc.configureHeadlessClient
import psymc.configureServerSmokeClient
import psymc.configureServerSmokeServer
import psymc.configureSmokeStop
import psymc.configureTestWorld
import psymc.fabricMinecraftPredicate
import psymc.loom
import psymc.modPin
import psymc.mojangMappings
import psymc.requiredProperty
import psymc.serverSmokePort
import psymc.writeServerFiles
import java.io.File

// Fabric pin leaf. The leaf build file applies Fabric Loom: fabric-loom-remap on an obfuscated pin,
// fabric-loom on a named pin.

apply(from = layout.settingsDirectory.file("gradle/mod-common.gradle.kts").asFile.path)
apply(from = layout.settingsDirectory.file("gradle/smoke.gradle.kts").asFile.path)

val pin = modPin()
val loaderVersion = requiredProperty("fabric_loader_version")

dependencies {
    add("minecraft", "com.mojang:minecraft:${pin.id}")
    // Fabric API is a required mod. The jar does not include it.
    val fabricApi = "net.fabricmc.fabric-api:fabric-api:${pin.fabricApi}"
    if (pin.namedJar) {
        add("compileOnly", "net.fabricmc:fabric-loader:$loaderVersion")
        add("runtimeOnly", "net.fabricmc:fabric-loader:$loaderVersion")
        add("implementation", fabricApi)
    } else {
        add("mappings", mojangMappings())
        add("modImplementation", "net.fabricmc:fabric-loader:$loaderVersion")
        add("modImplementation", fabricApi)
    }
    pin.libraries.forEach { library ->
        val notation = "maven.modrinth:${library.slug}:${library.fabric}"
        if (pin.namedJar) {
            add("implementation", notation) { isTransitive = false }
        } else {
            add("modImplementation", notation) { isTransitive = false }
        }
    }
}

// An access widener goes here: loom { setProperty("accessWidenerPath", file(...)) }.
// On a named pin, the header must say "official" instead of "named".

val metadata = mapOf(
    "mod_id" to requiredProperty("mod_id"),
    "mod_name" to requiredProperty("mod_name"),
    "mod_version" to requiredProperty("mod_version"),
    "mod_description" to requiredProperty("mod_description"),
    "mod_authors" to requiredProperty("mod_authors"),
    "mod_license" to requiredProperty("mod_license"),
    "minecraft_predicate" to fabricMinecraftPredicate(pin.minecraftRange),
    "java_version" to pin.java.toString(),
)

tasks.named<ProcessResources>("processResources") {
    inputs.properties(metadata)
    inputs.property("namedJar", pin.namedJar)
    filesMatching("fabric.mod.json") {
        expand(metadata)
        if (pin.namedJar) {
            // The loader is not a mod on this pin. Keep the dev mod on the classpath.
            filter { line -> if (line.contains("\"fabricloader\"")) "" else line }
        }
    }
}

val port = serverSmokePort("fabric")

loom {
    "runs" {
        "client" {
            val clientRun = if (project.hasProperty("serverSmoke")) "server-smoke-client" else "client"
            "runDir"(layout.buildDirectory.dir(clientRun).get().asFile.absolutePath)
            if (project.hasProperty("serverSmoke")) {
                "programArg"("--quickPlayMultiplayer")
                "programArg"("localhost:$port")
            }
        }
        "server" {
            "runDir"(layout.buildDirectory.dir("server").get().asFile.absolutePath)
            "programArg"("--nogui")
        }
    }
}

gradle.projectsEvaluated {
    val serverDir = layout.buildDirectory.dir("server").get().asFile
    val clientDir = layout.buildDirectory.dir("client").get().asFile
    val smokeClientDir = layout.buildDirectory.dir("server-smoke-client").get().asFile

    // Loom on a named pin does not see the class folders as a mod. Put the jar into mods/.
    fun JavaExec.copyNamedJars(gameDir: File) {
        if (!pin.namedJar) {
            return
        }
        dependsOn("jar")
        doFirst {
            val mods = File(gameDir, "mods")
            mods.mkdirs()
            val libs = layout.buildDirectory.dir("libs").get().asFile
            libs.listFiles()?.filter { it.name.endsWith(".jar") && !it.name.endsWith(".tmp") }?.forEach { jar ->
                jar.copyTo(File(mods, jar.name), overwrite = true)
            }
        }
    }

    tasks.named<JavaExec>("runServer").configure {
        copyNamedJars(serverDir)
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
            workingDir = smokeClientDir
            copyNamedJars(smokeClientDir)
            configureServerSmokeClient(this, smokeClientDir)
        } else {
            copyNamedJars(clientDir)
            // -Pplay keeps the window open for a person. The smoke run stays headless.
            if (!project.hasProperty("play")) {
                configureHeadlessClient(this)
                configureSmokeStop(this, clientDir, "Sound engine started", listOf("Error starting SoundSystem"))
            } else {
                configureTestWorld(this, clientDir)
            }
        }
    }
}
