import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.withGroovyBuilder
import psymc.HeadlessClient
import psymc.SMOKE_FAIL_MARKERS
import psymc.SmokeStop
import psymc.jdkTool
import psymc.whenTaskFinishes
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Launch helpers for the pin projects and the version-smoke launches.

// HeadlessMc patches LWJGL so the test client does not open a window.
configurations {
    create("headlessLwjgl") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    create("headlessAsm") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}

// HeadlessMc publishes the LWJGL jar as a GitHub release asset: headlessmc-lwjgl-<version>-no-asm.jar.
repositories {
    ivy {
        name = "HeadlessMc releases"
        url = uri("https://github.com/headlesshq/headlessmc/releases/download")
        patternLayout {
            artifact("[revision]/[module]-[revision]-[classifier].[ext]")
        }
        metadataSources {
            artifact()
        }
        content {
            includeGroup("headlesshq")
        }
    }
}

dependencies {
    // The dev jar does not shade ASM. Fabric already has one ASM on the game classpath.
    // A second copy inside the agent jar makes Fabric Loader refuse to start.
    add("headlessLwjgl", "headlesshq:headlessmc-lwjgl:${property("headlessmc_version")}:no-asm") { isTransitive = false }
    // The hidden window agent loads this ASM in its own class loader.
    add("headlessAsm", "org.ow2.asm:asm:9.7")
}

// Compile one agent from gradle/agents into a jar. Compile again when the source is newer.
// The agents are in the package psymc.agent. The obfuscated game has its classes in the root
// package, and its jar is signed: an unsigned class there stops the game with a SecurityException.
fun agentJar(name: String, manifest: String, classpath: List<File>): File {
    val jar = File(rootProject.projectDir, ".gradle/agents/$name.jar")
    val source = File(rootProject.projectDir, "gradle/agents/$name.java")
    if (jar.exists() && source.lastModified() <= jar.lastModified()) {
        return jar
    }
    val classDir = File(jar.parentFile, "$name-classes")
    classDir.deleteRecursively()
    classDir.mkdirs()
    val cp = if (classpath.isEmpty()) emptyList() else listOf("-cp", classpath.joinToString(File.pathSeparator))
    val compile = ProcessBuilder(
        listOf(jdkTool("javac").absolutePath, "--release", "17") + cp +
            listOf("-d", classDir.absolutePath, source.absolutePath),
    ).redirectErrorStream(true).start()
    val compileLog = compile.inputStream.bufferedReader().readText()
    if (compile.waitFor() != 0) {
        throw GradleException("$name did not compile. $compileLog")
    }
    val manifestFile = File(jar.parentFile, "$name.mf")
    manifestFile.writeText(manifest)
    val pack = ProcessBuilder(
        jdkTool("jar").absolutePath,
        "--create",
        "--file",
        jar.absolutePath,
        "--manifest",
        manifestFile.absolutePath,
        "-C",
        classDir.absolutePath,
        ".",
    ).redirectErrorStream(true).start()
    val packLog = pack.inputStream.bufferedReader().readText()
    if (pack.waitFor() != 0) {
        throw GradleException("$name jar was not created. $packLog")
    }
    return jar
}

// A game process belongs to this run when its command names the game directory or a dev launcher.
fun gameProcess(handle: ProcessHandle, gameDir: File): Boolean {
    val info = handle.info()
    val args = if (info.arguments().isPresent) info.arguments().get().joinToString(" ") else ""
    val cmd = (info.commandLine().orElse("") + " " + info.command().orElse("") + " " + args).lowercase()
    val dir = gameDir.absolutePath.lowercase()
    return cmd.contains(dir) ||
        cmd.contains("bootstraplauncher") ||
        cmd.contains("knotclient") ||
        cmd.contains("knotserver") ||
        cmd.contains("devlaunch")
}

// Kill the game processes that started after the baseline snapshot.
fun stopGameProcesses(gameDir: File, baseline: AtomicReference<Set<Long>?>) {
    val known = baseline.get() ?: return
    val matches = mutableListOf<ProcessHandle>()
    ProcessHandle.current().descendants().forEach { handle ->
        if (!known.contains(handle.pid()) && gameProcess(handle, gameDir)) {
            matches.add(handle)
        }
    }
    if (matches.isEmpty()) {
        ProcessHandle.current().descendants().forEach { handle ->
            if (known.contains(handle.pid())) {
                return@forEach
            }
            val cmd = handle.info().command().orElse("").lowercase()
            if (cmd.endsWith("java.exe") || cmd.endsWith("java")) {
                matches.add(handle)
            }
        }
    }
    matches.forEach { handle ->
        handle.descendants().forEach { child -> child.destroyForcibly() }
        handle.destroyForcibly()
    }
    if (!System.getProperty("os.name").lowercase().contains("windows")) {
        return
    }
    // On Windows the game can run outside the process tree of Gradle. Find it by its game directory.
    try {
        val finder = ProcessBuilder(
            "powershell",
            "-NoProfile",
            "-Command",
            "Get-CimInstance Win32_Process -Filter \"Name = 'java.exe'\" | Where-Object { \$_.CommandLine -like '*" +
                gameDir.absolutePath + "*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }",
        )
        finder.redirectErrorStream(true)
        finder.start().waitFor()
    } catch (_: IOException) {
        // The process handles above already received the stop.
    }
}

extra["configureHeadlessClient"] = HeadlessClient { task ->
    task.doFirst {
        val lwjgl = configurations.getByName("headlessLwjgl").singleFile
        val reads = agentJar(
            "ModuleReadsAgent",
            "Premain-Class: psymc.agent.ModuleReadsAgent\nCan-Retransform-Classes: true\nCan-Redefine-Classes: true\n",
            emptyList(),
        )
        // The LWJGL agent stays a normal javaagent.
        // The boot classpath cannot see the game ASM, so the transform would be skipped.
        // JVM --add-reads does not apply: NeoForge creates the LWJGL modules after startup.
        // The reads agent adds that edge when the LWJGL class loads.
        task.jvmArgs(
            "-javaagent:${reads.absolutePath}",
            "-javaagent:${lwjgl.absolutePath}",
            "-Djoml.nounsafe=true",
            "-Dfabric.systemLibraries=${lwjgl.absolutePath}",
            "-Dfabric.noGui=true",
            "-Dfml.earlyWindowControl=false",
            "-Dfml.earlyWindowSkip=true",
        )
        // FML reads earlyWindowControl from config/fml.toml only. The early window then runs on the
        // HeadlessMc GL stubs. On FML 6.0.18 (1.21.4) it can fail in a race. Turn it off in the file.
        // The run task gets its working directory at execution. Read the game directory of the task.
        if (pluginManager.hasPlugin("net.neoforged.moddev")) {
            val gameDir = (task.withGroovyBuilder { getProperty("gameDirectory") } as DirectoryProperty).get().asFile
            setTomlKey(File(gameDir, "config/fml.toml"), "earlyWindowControl", "false")
        }
    }
}

// Set one top-level key of a TOML file. Keep the other lines.
fun setTomlKey(file: File, key: String, value: String) {
    file.parentFile.mkdirs()
    val lines = if (file.exists()) {
        file.readLines().filterNot { it.trim().startsWith("$key ") || it.trim().startsWith("$key=") }
    } else {
        emptyList()
    }
    file.writeText((listOf("$key = $value") + lines).joinToString("\n") + "\n")
}

// A client with a real OpenGL context and no visible window.
// Sodium and Iris need that context, so HeadlessMc cannot replace LWJGL there.
// The agent keeps every GLFW window hidden. The Fabric error window stays closed too.
extra["configureHiddenWindow"] = HeadlessClient { task ->
    task.doFirst {
        val asm = configurations.getByName("headlessAsm").files.first { it.name.startsWith("asm-9") }
        val agent = agentJar("HiddenWindowAgent", "Premain-Class: psymc.agent.HiddenWindowAgent\n", listOf(asm))
        task.jvmArgs(
            "-javaagent:${agent.absolutePath}=${asm.absolutePath}",
            "-Dfabric.noGui=true",
        )
    }
}

// Stop a dev client or server after its output shows that it started.
extra["configureSmokeStop"] = SmokeStop { task, gameDir, success, extraMarkers, timeoutMs ->
    val markers = listOf(success) + extraMarkers
    val seen = AtomicBoolean(false)
    val closed = AtomicBoolean(false)
    val baseline = AtomicReference<Set<Long>?>(null)
    val output = object : OutputStream() {
        private val line = StringBuilder()

        override fun write(b: Int) {
            System.out.write(b)
            if (b == 10 || b == 13) {
                val text = line.toString()
                line.setLength(0)
                if (markers.any { marker -> text.contains(marker) } && seen.compareAndSet(false, true)) {
                    stopGameProcesses(gameDir, baseline)
                }
                if (SMOKE_FAIL_MARKERS.any { text.contains(it) }) {
                    stopGameProcesses(gameDir, baseline)
                }
                return
            }
            line.append(b.toChar())
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            for (index in off until off + len) {
                write(bytes[index].toInt() and 0xff)
            }
        }
    }
    task.isIgnoreExitValue = true
    whenTaskFinishes(task, closed)
    task.doFirst {
        val ids = mutableSetOf<Long>()
        ProcessHandle.current().descendants().forEach { handle -> ids.add(handle.pid()) }
        baseline.set(ids)
        File(gameDir, "logs/latest.log").delete()
        task.standardOutput = output
        task.errorOutput = output
        val watcher = Thread {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && !seen.get() && !closed.get()) {
                Thread.sleep(500)
            }
            if (!seen.get() && !closed.get()) {
                stopGameProcesses(gameDir, baseline)
            }
        }
        watcher.isDaemon = true
        watcher.start()
    }
    task.doLast {
        closed.set(true)
        if (!seen.get()) {
            throw GradleException("Smoke start did not reach $markers. Read ${File(gameDir, "logs/latest.log")}")
        }
    }
}
