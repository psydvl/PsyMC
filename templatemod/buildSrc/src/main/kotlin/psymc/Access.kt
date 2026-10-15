package psymc

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.GroovyBuilderScope
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withGroovyBuilder
import java.io.File

fun interface HeadlessClient {
    fun apply(task: JavaExec)
}

fun interface SmokeStop {
    fun apply(task: JavaExec, gameDir: File, success: String, extra: List<String>, timeoutMs: Long)
}

// The project of a pin leaf is :mod:<pin>:<loader>.
fun Project.modPin(): ModPin = modPins.firstOrNull { it.id == parent?.name }
    ?: throw GradleException("No pin in Versions.kt for $path")

fun modPinById(id: String): ModPin = modPins.firstOrNull { it.id == id }
    ?: throw GradleException("No pin $id in Versions.kt")

fun Project.selectedTarget(): TargetVersion {
    val raw = gradle.extra["selectedTarget"]
    if (raw is TargetVersion) {
        return raw
    }
    @Suppress("UNCHECKED_CAST")
    return TargetVersion(raw as Map<String, String>)
}

fun Project.requiredProperty(name: String): String = property(name).toString()

fun Project.javaLanguage(version: Int) {
    extensions.getByType<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(version))
}

fun Project.archivesName(name: String) {
    extensions.getByType<BasePluginExtension>().archivesName.set(name)
}

fun Project.sourceSets(): SourceSetContainer = extensions.getByType()

fun SourceDirectorySet.excludeIf(predicate: (FileTreeElement) -> Boolean) {
    exclude(Spec { details: FileTreeElement -> predicate(details) })
}

// Use the directories as layers, lowest first.
// A file in a higher layer replaces the file with the same relative path in a lower layer.
fun SourceDirectorySet.layers(dirs: List<File>) {
    setSrcDirs(dirs.filter { it.isDirectory })
    dirs.forEachIndexed { index, layer ->
        val higher = dirs.subList(index + 1, dirs.size).filter { it.isDirectory }
        if (higher.isEmpty() || !layer.isDirectory) {
            return@forEachIndexed
        }
        val base = layer.toPath()
        excludeIf { details ->
            val file = details.file.toPath()
            if (!details.file.isFile || !file.startsWith(base)) {
                false
            } else {
                val relative = base.relativize(file).toString()
                higher.any { above -> File(above, relative).isFile }
            }
        }
    }
}

fun Project.loom(block: GroovyBuilderScope.() -> Unit) {
    extensions.getByName("loom").withGroovyBuilder(block)
}

fun Project.neoForge(block: GroovyBuilderScope.() -> Unit) {
    extensions.getByName("neoForge").withGroovyBuilder(block)
}

fun Project.mojangMappings(): Any = extensions.getByName("loom").withGroovyBuilder {
    "officialMojangMappings"()
} ?: throw GradleException("Loom did not return Mojang mappings")

fun Project.whenTaskFinishes(task: org.gradle.api.Task, closed: java.util.concurrent.atomic.AtomicBoolean) {
    val method = gradle.taskGraph.javaClass.methods.first { candidate ->
        candidate.name == "afterTask" &&
            candidate.parameterTypes.size == 1 &&
            candidate.parameterTypes[0].simpleName == "Action"
    }
    val action = object : org.gradle.api.Action<org.gradle.api.Task> {
        override fun execute(finished: org.gradle.api.Task) {
            if (finished == task) {
                closed.set(true)
            }
        }
    }
    method.invoke(gradle.taskGraph, action)
}

// A tool of the JDK that runs Gradle: javac, jar, or java.
fun jdkTool(name: String): File {
    val home = System.getenv("JAVA_HOME")?.takeIf { it.isNotEmpty() } ?: System.getProperty("java.home")
    val windows = System.getProperty("os.name").lowercase().contains("windows")
    val tool = File(home, "bin/" + if (windows) "$name.exe" else name)
    if (!tool.exists()) {
        throw GradleException("The JDK at $home has no $name.")
    }
    return tool
}

fun Project.configureHeadlessClient(task: JavaExec) {
    (extra["configureHeadlessClient"] as HeadlessClient).apply(task)
}

fun Project.configureHiddenWindow(task: JavaExec) {
    (extra["configureHiddenWindow"] as HeadlessClient).apply(task)
}

fun Project.configureSmokeStop(
    task: JavaExec,
    gameDir: File,
    success: String,
    extraMarkers: List<String> = emptyList(),
    timeoutMs: Long = 120_000L,
) {
    (extra["configureSmokeStop"] as SmokeStop).apply(task, gameDir, success, extraMarkers, timeoutMs)
}

const val MIXIN_AUDIT_DONE = "MIXIN-AUDIT DONE"

// Lines that end a smoke launch as failed. The game can hang after some of them (a resource pack
// error waits at the loading screen), so the launch stops at once.
val SMOKE_FAIL_MARKERS = listOf(
    "Failed to start the minecraft server",
    "Failed to start FML",
    "InvalidModFileException",
    "Missing or unsupported mandatory dependencies",
    "Caught error loading resourcepacks",
    "Unreported exception thrown",
    "Reported exception",
    "Crashed!",
    "Preparing crash report",
    "full crash report file",
    // NilLoader: a required patch target is missing, or a patch method failed.
    "not found while patching",
    "Failed to patch",
)

// The lines that the nil patches print (gradle.properties: nil_smoke_markers).
fun Project.nilSmokeMarkers(): List<String> = findProperty("nil_smoke_markers")?.toString().orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

// The NeoForge jar of one release: the game patched by NeoForge, recompiled, with Mojang names.
// ModDevGradle keeps one for each NeoForge version it set up. The NeoForge smoke fills
// version-smoke/neoforge. A pin build fills its own build dir. Null before the first launch.
fun neoForgePatchedJar(root: File, item: TargetVersion): File? {
    val neo = item.properties["neoforge_loader"] ?: return null
    val names = listOf("neoforge-$neo.jar", "minecraft-patched-$neo.jar")
    val dirs = listOf(
        File(root, "version-smoke/neoforge/build/moddev/artifacts"),
        File(root, "mod/versions/${item.pin}/neoforge/build/moddev/artifacts"),
    )
    return dirs.flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile }
}

// The server smoke port of each loader. The pin projects use the same ports.
fun serverSmokePort(loader: String): Int = when (loader) {
    "neoforge" -> 25581
    else -> 25582
}

fun Project.smokeLoaderName(): String = when (val loader = findProperty("loader")?.toString()) {
    "neoforge", "nil" -> loader
    else -> "fabric"
}

// Put the pin jar and the extra jars into the mods folder of a game directory.
// Remove the old jars first. Copy all jars in this one action: a later doFirst runs earlier,
// so a separate copy action would run before the removal.
fun copyModJars(task: JavaExec, gameDir: File, pinJar: File?, extraJars: List<File> = emptyList()) {
    task.doFirst {
        val mods = File(gameDir, "mods")
        mods.listFiles()?.filter { it.name.endsWith(".jar") }?.forEach { it.delete() }
        mods.mkdirs()
        (listOfNotNull(pinJar) + extraJars).forEach { jar ->
            jar.copyTo(File(mods, jar.name), overwrite = true)
        }
    }
}

private fun Project.modrinthJar(slug: String, version: String): File {
    val configuration = configurations.detachedConfiguration(
        dependencies.create("maven.modrinth:$slug:$version"),
    )
    configuration.isTransitive = false
    return configuration.singleFile
}

// The libraries of the pin (Versions.kt) in the build for the selected release.
fun Project.libraryJars(): List<File> {
    val target = selectedTarget()
    val loader = smokeLoaderName()
    // The libraries are mods of Fabric and NeoForge. The vanilla launch of NilLoader has none.
    if (loader == "nil") {
        return emptyList()
    }
    return modPinById(target.pin).libraries.map { library ->
        modrinthJar(library.slug, target.library(library, loader))
    }
}

// -Pcompat=sodium,iris,dh adds these mods from Modrinth to a version-smoke launch.
// versionProperties names the Modrinth version id of each mod: compat_<mod>_<loader>.
// Iris needs Sodium, so iris adds sodium.
fun Project.compatModJars(): List<File> {
    val raw = findProperty("compat")?.toString().orEmpty()
    if (raw.isBlank()) {
        return emptyList()
    }
    val aliases = mapOf("dh" to "distanthorizons")
    val wanted = linkedSetOf<String>()
    raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { name ->
        val slug = aliases[name] ?: name
        if (slug == "iris") {
            wanted.add("sodium")
        }
        wanted.add(slug)
    }
    val loader = smokeLoaderName()
    val properties = selectedTarget().properties
    // A release without an id for a mod has no usable build of it. The launch then runs without that mod.
    return wanted.mapNotNull { slug ->
        val key = "compat_${slug}_$loader"
        val versionId = properties[key]
        if (versionId == null) {
            logger.warn("-Pcompat: no $key in versionProperties for ${selectedTarget().minecraftVersion}. This launch has no $slug.")
            return@mapNotNull null
        }
        modrinthJar(slug, versionId)
    }
}

// The libraries and the -Pcompat mods for a Fabric launch that puts them on the classpath through Loom.
// Loom remaps them once and caches them. The runtime remap of Fabric Loader fails on some jars
// (ClosedFileSystemException).
// Loom does not add the nested jars (META-INF/jars) of a file dependency. So this function
// extracts them too. When two mods nest one module, the higher version stays.
// A mod can nest the whole Fabric API jar. Only its modules go on the classpath.
// -PcompatMods puts the mods into mods/ instead. Fabric Loader then remaps them at run time.
fun Project.classpathModJars(): List<File> {
    val mods = libraryJars() + compatModJars()
    if (mods.isEmpty() || hasProperty("compatMods")) {
        return emptyList()
    }
    val nestedDir = File(rootProject.projectDir, "build/version-smoke/nested/${selectedTarget().minecraftVersion}")
    nestedDir.mkdirs()
    val nameAndVersion = Regex("^(.+?)-(\\d.*)\\.jar$")
    val chosen = linkedMapOf<String, Pair<String, File>>()
    val pending = ArrayDeque(mods)
    while (pending.isNotEmpty()) {
        java.util.zip.ZipFile(pending.removeFirst()).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("META-INF/jars/") && it.name.endsWith(".jar") }
                .forEach { entry ->
                    val fileName = entry.name.substringAfterLast('/')
                    val match = nameAndVersion.find(fileName)
                    val module = match?.groupValues?.get(1) ?: fileName
                    val version = match?.groupValues?.get(2).orEmpty()
                    val known = chosen[module]
                    if (known != null && compareLooseVersions(known.first, version) >= 0) {
                        return@forEach
                    }
                    val target = File(nestedDir, fileName)
                    if (!target.isFile) {
                        zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                    chosen[module] = version to target
                    pending.add(target)
                }
        }
    }
    return mods + chosen.filterKeys { it != "fabric-api" }.values.map { it.second }
}

// Compare the number parts of two version strings. Text parts compare as text.
private fun compareLooseVersions(a: String, b: String): Int {
    val left = a.split('.', '+', '-')
    val right = b.split('.', '+', '-')
    for (index in 0 until maxOf(left.size, right.size)) {
        val x = left.getOrElse(index) { "" }
        val y = right.getOrElse(index) { "" }
        val xNumber = x.toIntOrNull()
        val yNumber = y.toIntOrNull()
        val result = if (xNumber != null && yNumber != null) xNumber.compareTo(yNumber) else x.compareTo(y)
        if (result != 0) {
            return result
        }
    }
    return 0
}

// With iris in -Pcompat, put the shader pack into the client and turn it on.
// Without a pack Iris does not use its own render paths.
// compat_shaderpack names the pack: Modrinth project:version id. versionProperties can replace it.
// -PnoShaderpack keeps Iris without a pack.
fun Project.configureCompatShaderpack(task: JavaExec, clientDir: File) {
    val compat = findProperty("compat")?.toString().orEmpty().lowercase()
    if (!compat.split(",").map { it.trim() }.contains("iris") || hasProperty("noShaderpack")) {
        return
    }
    val coordinate = selectedTarget().properties["compat_shaderpack"]
        ?: findProperty("compat_shaderpack")?.toString()
        ?: throw GradleException("No compat_shaderpack in gradle.properties.")
    val (slug, versionId) = coordinate.split(":", limit = 2)
    val configuration = configurations.detachedConfiguration(
        dependencies.create("maven.modrinth:$slug:$versionId@zip"),
    )
    configuration.isTransitive = false
    val pack = configuration.singleFile
    task.doFirst {
        val packs = File(clientDir, "shaderpacks")
        packs.listFiles()?.forEach { it.delete() }
        packs.mkdirs()
        pack.copyTo(File(packs, pack.name), overwrite = true)
        val config = File(clientDir, "config/iris.properties")
        config.parentFile.mkdirs()
        config.writeText("enableShaders=true\nshaderPack=${pack.name}\n")
    }
}

// A NeoForge dev client wraps the GPU device in a validation layer.
// Iris casts the GPU device to GlDevice and fails on that wrapper. So -Pcompat turns the layer off.
// The edit keeps the other keys of the client config.
fun Project.configureCompatNeoForge(task: JavaExec, clientDir: File) {
    if (findProperty("compat")?.toString().orEmpty().isBlank() || smokeLoaderName() != "neoforge") {
        return
    }
    task.doFirst {
        val config = File(clientDir, "config/neoforge-client.toml")
        config.parentFile.mkdirs()
        val key = "enableB3DValidationLayer"
        val lines = if (config.exists()) {
            config.readLines().filterNot { it.trim().startsWith("$key ") || it.trim().startsWith("$key=") }
        } else {
            emptyList()
        }
        config.writeText((listOf("$key = false") + lines).joinToString("\n") + "\n")
    }
}

// -PtestWorld=<name>: copy the prepared world testworlds/<name> into saves/<name> of the client and
// open it at start (--quickPlaySingleplayer). Each launch starts from a fresh copy;
// -PkeepTestWorld keeps the copy of the last launch. testworlds/README.md tells how to make one.
fun Project.configureTestWorld(task: JavaExec, clientDir: File) {
    val name = findProperty("testWorld")?.toString()?.takeIf { it.isNotBlank() } ?: return
    val source = File(rootProject.projectDir, "testworlds/$name")
    if (!File(source, "level.dat").isFile) {
        throw GradleException("No test world $source (no level.dat). Read testworlds/README.md.")
    }
    val keep = hasProperty("keepTestWorld")
    task.args("--quickPlaySingleplayer", name)
    val hotbar = File(rootProject.projectDir, "testworlds/hotbar.nbt")
    task.doFirst {
        val target = File(clientDir, "saves/$name")
        if (!keep || !target.isDirectory) {
            target.deleteRecursively()
            source.copyRecursively(target)
        }
        // The saved hotbars of the creative inventory (C+1 loads the first one).
        val clientHotbar = File(clientDir, "hotbar.nbt")
        if (hotbar.isFile && (!keep || !clientHotbar.isFile)) {
            clientDir.mkdirs()
            hotbar.copyTo(clientHotbar, overwrite = true)
        }
    }
}

// The NeoForge load warning screen waits for a click. Quick play starts after that screen.
fun hideNeoForgeLoadWarnings(task: JavaExec, clientDir: File) {
    task.doFirst {
        val clientConfig = File(clientDir, "config/neoforge-client.toml")
        clientConfig.parentFile.mkdirs()
        clientConfig.writeText("showLoadWarnings = false\n")
    }
}

// Put the mod jars into the client and stop the client at its start marker.
// A null pin jar means the launch already has the pin on its classpath.
// -PmixinAudit adds the audit mod. The client then stops after the audit.
// -PserverSmoke makes a headless client that joins the server smoke server.
// -Pplay keeps the window open for a person. The client does not stop by itself.
// -Pcompat adds other mods (see compatModJars).
// modsOnClasspath means the launch already has the libraries and the -Pcompat mods on its classpath.
fun Project.configureClientSmoke(task: JavaExec, clientDir: File, pinJar: File?, modsOnClasspath: Boolean = false) {
    val audit = hasProperty("mixinAudit")
    val auditJar = File(rootProject.projectDir, "version-smoke/mixin-audit/build/libs/mixin-audit.jar")
    if (audit) {
        task.dependsOn(":version-smoke:mixin-audit:jar")
        task.systemProperty("mixinAudit.configs", requiredProperty("mixin_configs"))
    }
    val launchJars = if (modsOnClasspath && !hasProperty("compatMods")) emptyList() else libraryJars() + compatModJars()
    val extraJars = (if (audit) listOf(auditJar) else emptyList()) + launchJars
    copyModJars(task, clientDir, pinJar, extraJars)
    configureCompatShaderpack(task, clientDir)
    configureCompatNeoForge(task, clientDir)
    if (hasProperty("serverSmoke")) {
        val loader = smokeLoaderName()
        task.args("--quickPlayMultiplayer", "localhost:${serverSmokePort(loader)}")
        configureServerSmokeClient(task, clientDir)
        if (loader == "neoforge") {
            hideNeoForgeLoadWarnings(task, clientDir)
        }
        return
    }
    if (hasProperty("play")) {
        configureTestWorld(task, clientDir)
        return
    }
    // -Pheaded opens a window. The default smoke stays headless.
    // Sodium and Iris need a real OpenGL context. -Pcompat keeps that context and hides the window.
    // -PrealGl does the same without other mods. Use it to check render code.
    val realGl = findProperty("compat")?.toString().orEmpty().isNotBlank() || hasProperty("realGl")
    if (!hasProperty("headed")) {
        if (realGl) {
            configureHiddenWindow(task)
        } else {
            configureHeadlessClient(task)
        }
    }
    if (audit) {
        configureSmokeStop(task, clientDir, MIXIN_AUDIT_DONE, emptyList(), 600_000L)
    } else {
        configureSmokeStop(task, clientDir, "Sound engine started", listOf("Error starting SoundSystem"), 600_000L)
    }
}

// Write a fresh offline server directory.
fun writeServerFiles(serverDir: File, port: Int, smoke: Boolean) {
    if (smoke) {
        File(serverDir, "world").deleteRecursively()
    }
    serverDir.mkdirs()
    File(serverDir, "eula.txt").writeText("eula=true\n")
    val lines = mutableListOf("online-mode=false", "server-port=$port")
    if (smoke) {
        lines += listOf("level-type=minecraft\\:flat", "generate-structures=false", "spawn-protection=0")
    }
    File(serverDir, "server.properties").writeText(lines.joinToString("\n") + "\n")
}

// -PserverSmoke starts a dedicated server for the root serverSmoke task.
// The server reads its console commands from the Gradle standard input.
fun configureServerSmokeServer(task: JavaExec, serverDir: File, port: Int) {
    task.standardInput = System.`in`
    task.doFirst {
        writeServerFiles(serverDir, port, true)
    }
}

// The dedicated server of a version-smoke launch. It runs the pin jar on one release.
// A null pin jar means the launch already has the pin on its classpath.
// -PserverSmoke reads the console from the root serverSmoke task.
// Without -PserverSmoke the server stops when it is up, unless -Pplay keeps it running.
fun Project.configureServerSmokeLeaf(task: JavaExec, serverDir: File, pinJar: File?, extraJars: List<File>) {
    val port = serverSmokePort(smokeLoaderName())
    copyModJars(task, serverDir, pinJar, extraJars)
    if (hasProperty("serverSmoke")) {
        configureServerSmokeServer(task, serverDir, port)
        return
    }
    task.doFirst {
        writeServerFiles(serverDir, port, false)
    }
    if (!hasProperty("play")) {
        configureSmokeStop(task, serverDir, "For help, type \"help\"")
    }
}

// -PserverSmoke starts a headless client. The run arguments join the dedicated server.
// The root serverSmoke task stops this client.
fun Project.configureServerSmokeClient(task: JavaExec, clientDir: File) {
    task.isIgnoreExitValue = true
    // -PrealGl keeps a real OpenGL context with a hidden window. Then the client draws the world.
    if (hasProperty("realGl")) {
        configureHiddenWindow(task)
    } else {
        configureHeadlessClient(task)
    }
    task.doFirst {
        clientDir.mkdirs()
        File(clientDir, "options.txt").writeText(
            "pauseOnLostFocus:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\nlang:en_us\n",
        )
        File(clientDir, "logs/latest.log").delete()
    }
}

fun JavaCompile.useErrorProne(vararg checks: String) {
    (options as ExtensionAware).extensions.getByName("errorprone").withGroovyBuilder {
        getProperty("enabled").withGroovyBuilder { "set"(true) }
        getProperty("disableAllChecks").withGroovyBuilder { "set"(true) }
        "error"(*checks)
    }
}

fun Project.neoForgeClientRun(): Any = (
    extensions.getByName("neoForge").withGroovyBuilder { getProperty("runs") }
        as org.gradle.api.NamedDomainObjectContainer<*>
    ).getByName("client")

fun Project.neoForgeServerRun(): Any = (
    extensions.getByName("neoForge").withGroovyBuilder { getProperty("runs") }
        as org.gradle.api.NamedDomainObjectContainer<*>
    ).getByName("server")

fun Any.configurationName(propertyName: String): String {
    val configuration = withGroovyBuilder { getProperty(propertyName) } as Configuration
    return configuration.name
}
