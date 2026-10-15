import org.gradle.api.GradleException
import psymc.MIXIN_AUDIT_DONE
import psymc.MinecraftRelease
import psymc.SMOKE_FAIL_MARKERS
import psymc.TargetVersion
import psymc.compareMinecraftVersions
import psymc.jdkTool
import psymc.loadTargetVersions
import psymc.neoForgePatchedJar
import psymc.nilSmokeMarkers
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

// Root checks that start nested Gradle runs.
// Each nested run is a new process with --no-daemon. The current build already holds the daemon.
// - versionSmoke, mixinAudit: one pin jar, then one launch per target release.
// - serverSmoke: a dedicated server and a headless client; the client joins through TCP.
// - versionPlay: one pin jar on one release with a window.
// - mixinCheck: mixin selectors against the game jar. No game starts.

fun smokeLoader(): String {
    if (!project.hasProperty("loader")) {
        return "fabric"
    }
    val name = project.property("loader").toString().trim()
    val enabled = property("loaders").toString().split(",").map { it.trim() }
    if (name !in enabled) {
        throw GradleException("loader must be one of $enabled (gradle.properties: loaders).")
    }
    return name
}

val smokeRoot = rootProject.projectDir
val smokeOut = layout.buildDirectory.dir("version-smoke").get().asFile
val smokeReport = File(smokeOut, "report-${smokeLoader()}.md")
val smokeLogs = File(smokeOut, "logs/${smokeLoader()}")
val smokeJars = File(smokeOut, "jars")
val modId = property("mod_id").toString()

val smokeSkip = listOf(
    "restricted method",
    "restricted methods will be blocked",
    "enable-native-access",
    "deprecated gradle features",
    "warning-mode all",
    "incubating",
    "consider enabling configuration cache",
)

fun smokePropertyList(name: String): Set<String> {
    if (!project.hasProperty(name)) {
        return emptySet()
    }
    val raw = project.property(name).toString().trim()
    if (raw.isEmpty()) {
        return emptySet()
    }
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

fun smokeKind(line: String): String? {
    val low = line.lowercase()
    if (smokeSkip.any { low.contains(it) }) {
        return null
    }
    if (low.contains("deprecat") || line.trim().startsWith("Note:")) {
        return "deprecation"
    }
    if (low.contains("/error]") || low.contains(" error:") || low.startsWith("error") || low.contains("exception")) {
        return "error"
    }
    if (low.contains("/warn]") || low.contains(" warning:") || low.startsWith("warning")) {
        return "warning"
    }
    return null
}

fun smokeCollect(text: String): Map<String, List<String>> {
    val buckets = mutableMapOf(
        "error" to mutableListOf<String>(),
        "warning" to mutableListOf<String>(),
        "deprecation" to mutableListOf<String>(),
    )
    val seen = mutableSetOf<String>()
    text.lineSequence().forEach { raw ->
        val line = raw.replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
        if (line.isEmpty() || seen.contains(line)) {
            return@forEach
        }
        val kind = smokeKind(line) ?: return@forEach
        seen.add(line)
        val bucket = buckets.getValue(kind)
        if (bucket.size < 80) {
            bucket.add(line)
        }
    }
    return buckets
}

fun smokeSection(title: String, lines: List<String>): String {
    if (lines.isEmpty()) {
        return "### $title\n\nNone.\n"
    }
    val body = lines.joinToString("\n") { "- $it" }
    return "### $title\n\n$body\n"
}

fun byRelease(items: List<TargetVersion>): List<TargetVersion> = items.sortedWith { left, right -> compareMinecraftVersions(left.minecraftVersion, right.minecraftVersion) }

// -Ppin=<pin>[,<pin>]: every release of these pins.
// -Pmc=<release>[,<release>]: only these releases.
// -PmainOthers: with -Ppin, the own release of the other pins too.
fun smokeSelect(all: List<TargetVersion>): List<TargetVersion> {
    val loader = smokeLoader()
    val versions = all.filter { runsOn(it, loader) }
    val pins = smokePropertyList("pin")
    val wanted = smokePropertyList("mc")
    val mainOthers = project.hasProperty("mainOthers")
    if (pins.isEmpty() && wanted.isEmpty() && !mainOthers) {
        return versions
    }
    return byRelease(
        versions.filter { item ->
            val ownPin = item.minecraftVersion == item.pin
            when {
                pins.isNotEmpty() && pins.contains(item.pin) -> wanted.isEmpty() || wanted.contains(item.minecraftVersion)
                mainOthers && ownPin && !pins.contains(item.pin) -> true
                else -> pins.isEmpty() && wanted.contains(item.minecraftVersion)
            }
        },
    )
}

data class SmokeCompleted(val exit: Int, val text: String)

// These properties pass on to the nested launch. See compatModJars in buildSrc.
fun smokeCompatArgs(): List<String> {
    val args = mutableListOf<String>()
    val compat = project.findProperty("compat")?.toString().orEmpty()
    if (compat.isNotBlank()) {
        args.add("-Pcompat=$compat")
    }
    listOf("noShaderpack", "compatMods", "realGl").filter { project.hasProperty(it) }.forEach { args.add("-P$it") }
    return args
}

fun smokeGradleCommand(taskArgs: List<String>): List<String> {
    val extra = mutableListOf("--console=plain", "--no-daemon")
    val paths = System.getProperty("org.gradle.java.installations.paths")
    if (paths != null) {
        extra.add("-Dorg.gradle.java.installations.paths=$paths")
    }
    val command = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
        // Give the full path. NoDefaultCurrentDirectoryInExePath stops cmd from a search in the work dir.
        listOf("cmd", "/c", File(smokeRoot, "gradlew.bat").absolutePath) + taskArgs + extra
    } else {
        listOf(File(smokeRoot, "gradlew").absolutePath) + taskArgs + extra
    }
    return command.map { it.toString() }
}

fun smokeGradle(taskArgs: List<String>, logFile: File): SmokeCompleted {
    val process = ProcessBuilder(smokeGradleCommand(taskArgs))
        .directory(smokeRoot)
        .redirectErrorStream(true)
        .start()
    val out = ByteArrayOutputStream()
    val pump = Thread {
        process.inputStream.transferTo(out)
    }
    pump.isDaemon = true
    pump.start()
    val finished = process.waitFor(20, TimeUnit.MINUTES)
    if (!finished) {
        process.destroyForcibly()
    }
    pump.join(10_000)
    var text = out.toString(Charsets.UTF_8)
    if (!finished) {
        text += "\nversion smoke timed out after 20 minutes\n"
    }
    logFile.parentFile.mkdirs()
    logFile.writeText(text)
    return SmokeCompleted(if (finished) process.exitValue() else 1, text)
}

// Build the production jar of one pin and copy it to build/version-smoke/jars/<pin>-<loader>.jar.
fun smokeCopyPinJar(pin: String, named: Boolean, loader: String) {
    smokeJars.mkdirs()
    val task = if (loader != "fabric" || named) "jar" else "remapJar"
    val logFile = File(smokeLogs, "build-$pin.txt")
    logger.lifecycle("BUILD pin $pin $loader $task")
    val completed = smokeGradle(listOf(":mod:$pin:$loader:$task"), logFile)
    if (completed.exit != 0) {
        throw GradleException("Pin $pin jar failed. Read $logFile")
    }
    val libs = File(smokeRoot, "mod/versions/$pin/$loader/build/libs")
    val jars = libs.listFiles()?.filter { file ->
        file.name.endsWith(".jar") &&
            !file.name.contains("sources") &&
            !file.name.contains("javadoc") &&
            !file.name.endsWith(".tmp")
    }.orEmpty()
    if (jars.isEmpty()) {
        throw GradleException("No jar in $libs")
    }
    val newest = jars.maxBy { it.lastModified() }
    val dest = File(smokeJars, "$pin-$loader.jar")
    newest.copyTo(dest, overwrite = true)
}

data class SmokeBlock(val block: String, val status: String)

// NilLoader runs on the vanilla game. Every release has it.
fun runsOn(item: TargetVersion, loader: String): Boolean = loader == "nil" || loader in item.loaders

// The nil jar has no Mixin.
fun requireModLoader(task: String) {
    if (smokeLoader() == "nil") {
        throw GradleException("$task does not support -Ploader=nil.")
    }
}

fun smokeLeaf(item: TargetVersion, loader: String): String = when {
    loader == "neoforge" -> "neoforge"
    loader == "nil" -> "vanilla"
    item.obfuscated -> "obfuscated"
    else -> "named"
}

fun smokeRunOne(item: TargetVersion, built: MutableSet<String>, audit: Boolean): SmokeBlock {
    val mc = item.minecraftVersion
    val pin = item.pin
    val loader = smokeLoader()
    smokeLogs.mkdirs()
    if (!built.contains(pin)) {
        smokeCopyPinJar(pin, !item.obfuscated, loader)
        built.add(pin)
    }
    val leaf = smokeLeaf(item, loader)
    val logFile = File(smokeLogs, if (audit) "audit-$mc.txt" else "$mc.txt")
    logger.lifecycle("RUN $mc pin $pin $loader")
    val headed = if (project.hasProperty("headed")) listOf("-Pheaded") else emptyList()
    val auditArg = (if (audit) listOf("-PmixinAudit") else emptyList()) + smokeCompatArgs()
    val completed = smokeGradle(
        listOf(":version-smoke:$leaf:runClient", "-PversionSmoke", "-Ploader=$loader", "-Pmc_ver=$mc") +
            headed + auditArg,
        logFile,
    )
    if (audit) {
        return auditBlock(item, loader, completed)
    }
    val reachedSound = completed.text.contains("Sound engine started")
    // HeadlessMc has no OpenAL 1.1. The client then reports a sound error at the same point.
    // A mod can add an OpenAL stub for the smoke; then the client reaches "Sound engine started".
    val headlessOpenAl = completed.text.contains("Error starting SoundSystem")
    // FML can refuse the pin jar and still start the client without it.
    val lines = completed.text.lineSequence().map { it.replace(Regex("\u001B\\[[0-9;]*m"), "").trim() }.toList()
    val failure = lines.firstOrNull { line -> SMOKE_FAIL_MARKERS.any { line.contains(it) } }
    // A nil launch must show the lines of its patches (gradle.properties: nil_smoke_markers).
    val missingMarkers = if (loader == "nil") nilSmokeMarkers().filter { !completed.text.contains(it) } else emptyList()
    val started = (reachedSound || headlessOpenAl) && failure == null && missingMarkers.isEmpty() && completed.exit == 0
    // Mixin drops a failed mixin and the game goes on. Show it, but do not fail the release.
    val mixinFailures = lines.filter { it.contains("Mixin apply for mod $modId failed") }.distinct()
    val buckets = smokeCollect(completed.text)
    val status = if (started) "started" else "failed"
    val block = listOf(
        "## $mc",
        "",
        "Pin `$pin`. Loader `$loader`. Status: $status. Gradle exit ${completed.exit}.",
        "",
        smokeSection("Failure", listOfNotNull(failure)),
        if (loader == "nil") smokeSection("Missing patch lines", missingMarkers) else "",
        smokeSection("Mixin apply failures of this mod", mixinFailures),
        smokeSection("Errors", buckets.getValue("error")),
        smokeSection("Warnings", buckets.getValue("warning")),
        smokeSection("Deprecations", buckets.getValue("deprecation")),
    ).joinToString("\n") + "\n"
    return SmokeBlock(block, status)
}

val auditFail = "MIXIN-AUDIT FAIL"

fun auditBlock(item: TargetVersion, loader: String, completed: SmokeCompleted): SmokeBlock {
    val lines = completed.text.lineSequence().map { it.replace(Regex("\u001B\\[[0-9;]*m"), "").trim() }.toList()
    val done = lines.firstOrNull { it.contains("$MIXIN_AUDIT_DONE configs=") }
    val failures = lines.filter { it.contains(auditFail) }.map { it.substring(it.indexOf(auditFail)) }.distinct()
    val buckets = smokeCollect(completed.text)
    val found = buckets.getValue("error") + buckets.getValue("warning")
    val mixinLines = found.filter { it.lowercase().contains("mixin") && !it.contains("MIXIN-AUDIT") }
    val otherErrors = buckets.getValue("error").filter { it !in mixinLines && !it.contains("MIXIN-AUDIT") }
    val status = when {
        done == null || completed.exit != 0 || done.contains("configs=0") -> "failed"
        failures.isEmpty() && mixinLines.isEmpty() -> "clean"
        else -> "issues"
    }
    val result = done?.substring(done.indexOf(MIXIN_AUDIT_DONE)) ?: "The audit did not finish."
    val block = listOf(
        "## ${item.minecraftVersion}",
        "",
        "Pin `${item.pin}`. Loader `$loader`. Status: $status. Gradle exit ${completed.exit}. `$result`",
        "",
        smokeSection("Audit failures", failures),
        smokeSection("Mixin messages", mixinLines),
        smokeSection("Other errors", otherErrors),
    ).joinToString("\n") + "\n"
    return SmokeBlock(block, status)
}

// Known failures of the smoke tasks: gradle/smoke-expected.txt. One entry per line:
//   <task or *> | <loader or *> | <release> | <reason, with a link>
data class ExpectedFailure(val task: String, val loader: String, val release: String, val reason: String)

val expectedFailures: List<ExpectedFailure> = File(smokeRoot, "gradle/smoke-expected.txt").takeIf { it.isFile }
    ?.readLines().orEmpty()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .map { line ->
        val parts = line.split("|").map { it.trim() }
        if (parts.size < 4) {
            throw GradleException("gradle/smoke-expected.txt: need 4 fields: $line")
        }
        ExpectedFailure(parts[0], parts[1], parts[2], parts.drop(3).joinToString(" | "))
    }

fun expectedFailure(task: String, loader: String, release: String): ExpectedFailure? = expectedFailures.firstOrNull {
    (it.task == "*" || it.task == task) && (it.loader == "*" || it.loader == loader) && it.release == release
}

// Run the selected releases one after another and write one report.
// A failed release in gradle/smoke-expected.txt counts as "expected" and does not fail the task.
// An expected release that passes is reported: remove its entry.
fun runReleases(
    task: String,
    report: File,
    header: List<String>,
    statuses: List<String>,
    versions: List<TargetVersion>,
    run: (TargetVersion) -> SmokeBlock,
) {
    val loader = smokeLoader()
    smokeOut.mkdirs()
    report.writeText(header.joinToString("\n") + "\n\n")
    val counts = linkedMapOf<String, Int>()
    statuses.forEach { counts[it] = 0 }
    versions.forEach { item ->
        val result = try {
            run(item)
        } catch (error: RuntimeException) {
            SmokeBlock(
                "## ${item.minecraftVersion}\n\nPin `${item.pin}`. Loader `$loader`. Status: failed.\n\n" +
                    "### Failure\n\n- ${error.message}\n",
                "failed",
            )
        }
        val expected = expectedFailure(task, loader, item.minecraftVersion)
        val status = when {
            expected == null -> result.status
            result.status == "failed" -> "expected"
            else -> result.status.also {
                logger.warn("${item.minecraftVersion} is in gradle/smoke-expected.txt but did not fail. Remove the entry.")
            }
        }
        val note = when {
            expected == null -> ""
            status == "expected" -> "Expected failure: ${expected.reason}\n\n"
            else -> "In gradle/smoke-expected.txt, but it did not fail. Remove the entry. (${expected.reason})\n\n"
        }
        counts[status] = counts.getOrDefault(status, 0) + 1
        report.appendText(result.block.replaceFirst("\n\n", "\n\n$note") + "\n")
        logger.lifecycle("DONE ${item.minecraftVersion} $status")
    }
    val summary = counts.entries.joinToString(" ") { (name, count) ->
        name.replaceFirstChar { it.uppercase() } + " $count."
    }
    report.appendText(summary + "\n")
    logger.lifecycle(summary)
    logger.lifecycle("Report $report")
    // The check fails after the report is complete. Then CI can use it as a gate.
    val failed = counts.getOrDefault("failed", 0)
    if (failed > 0) {
        throw GradleException("$failed release(s) failed. Read $report")
    }
}

tasks.register("versionSmoke") {
    group = "verification"
    description = "Start each pin jar on the releases in versionProperties. -Ploader chooses fabric or neoforge."
    doLast {
        val built = mutableSetOf<String>()
        runReleases(
            "versionSmoke",
            smokeReport,
            listOf(
                "# Version smoke",
                "",
                "Each pin is built once. This file records the ${smokeLoader()} jar of that pin",
                "on each selected Minecraft release in `versionProperties`.",
                "A release counts as started when the headless client reaches the sound startup:",
                "`Sound engine started`, or `Error starting SoundSystem` (HeadlessMc has no OpenAL 1.1).",
            ),
            listOf("started", "failed"),
            smokeSelect(loadTargetVersions(smokeRoot)),
        ) { item -> smokeRunOne(item, built, false) }
    }
}

tasks.register("mixinAudit") {
    group = "verification"
    description = "Start each pin jar with the audit mod. The mod loads every mixin target and stops the client."
    doLast {
        requireModLoader("mixinAudit")
        val built = mutableSetOf<String>()
        runReleases(
            "mixinAudit",
            File(smokeOut, "audit-${smokeLoader()}.md"),
            listOf(
                "# Mixin audit",
                "",
                "Each pin is built once. The audit mod loads every mixin target of the ${smokeLoader()} jar",
                "on each selected Minecraft release. The client stops at `$MIXIN_AUDIT_DONE`.",
                "Mixin messages come from the game output of that launch.",
            ),
            listOf("clean", "issues", "failed"),
            smokeSelect(loadTargetVersions(smokeRoot)),
        ) { item -> smokeRunOne(item, built, true) }
    }
}

// Network smoke. A dedicated server and a headless client of one pin run at the same time.
// The client joins through TCP. This task sends the scenario to the server console.

// The steps after the client joins. Change them for your mod (docs/testing.md, serverSmoke).
// command: a server console command, or null.
// side, expect: the output ("server" or "client") must show this text after the command.
// waitMs: a pause after the step. A connection error in this pause fails the run.
data class ScenarioStep(
    val name: String,
    val command: String?,
    val side: String,
    val expect: String,
    val waitMs: Long = 0,
    // The loaders of the step. Null: all. The nil jar has no block of the mod.
    val loaders: Set<String>? = null,
)

// The loaders whose jar has the mod block.
val modBlockLoaders = setOf("fabric", "neoforge")

val serverScenario = listOf(
    ScenarioStep("the mod started on the server", null, "server", "[$modId] server started"),
    ScenarioStep("server chat reaches the client", "say psymc-smoke-chat", "client", "psymc-smoke-chat"),
    // A block of the mod goes into a chunk. The client must get that chunk again: leave the
    // dimension and come back. A block state without a numeric id breaks the chunk packet, and the
    // client disconnects.
    ScenarioStep("creative mode", "gamemode creative @a", "server", "game mode"),
    ScenarioStep("place the mod block", "setblock 2 -60 2 $modId:probe_block", "server", "Changed the block", loaders = modBlockLoaders),
    ScenarioStep("the mod block is there", "execute if block 2 -60 2 $modId:probe_block", "server", "Test passed", loaders = modBlockLoaders),
    ScenarioStep("go to the nether", "execute in minecraft:the_nether run tp @a 0 100 0", "server", "Teleported", 5000),
    ScenarioStep("come back to the block", "execute in minecraft:overworld run tp @a 2 -59 4", "server", "Teleported", 5000),
)

val serverSmokeFailMarkers = listOf(
    "lost connection",
    "disconnected with reason",
    "Failed to handle packet",
    "Error sending packet",
    "Error receiving packet",
    "Exception caught in connection",
    "DecoderException",
    "EncoderException",
    "Unknown or incomplete command",
    "Incorrect argument",
    "Failed to start the minecraft server",
    "Failed to start FML",
    "InvalidModFileException",
    "Missing or unsupported mandatory dependencies",
    "Reported exception",
    "Crashed!",
    "Game crashed!",
    "full crash report",
    "BUILD FAILED",
)

class ServerSmokeFail(message: String) : RuntimeException(message)

// One Gradle process. The pump keeps each output line and writes the log.
class ServerSmokeProcess(val name: String, command: List<String>, dir: File, log: File) {
    val process: Process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).start()
    private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val pump = Thread {
        log.parentFile.mkdirs()
        log.bufferedWriter().use { out ->
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                val line = raw.replace(Regex("\u001B\\[[0-9;]*m"), "")
                lines.add(line)
                out.write(line)
                out.newLine()
                out.flush()
            }
        }
    }

    init {
        pump.isDaemon = true
        pump.start()
    }

    fun size(): Int = lines.size

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun find(from: Int, test: (String) -> Boolean): String? = synchronized(lines) {
        lines.drop(from).firstOrNull(test)
    }

    fun failure(markers: List<String>): String? = find(0) { line -> markers.any { line.contains(it) } }

    fun send(command: String) {
        println("CONSOLE> $command")
        process.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    fun kill() {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        pump.join(10_000)
    }
}

// Wait for one line in the target output. Any failure line in any output stops the wait.
fun serverSmokeAwait(
    step: String,
    timeoutMs: Long,
    all: List<ServerSmokeProcess>,
    target: ServerSmokeProcess,
    from: Int,
    test: (String) -> Boolean,
) {
    logger.lifecycle("STEP $step")
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        all.forEach { proc ->
            val failure = proc.failure(serverSmokeFailMarkers)
            if (failure != null) {
                throw ServerSmokeFail("$step: ${proc.name}: $failure")
            }
        }
        if (target.find(from, test) != null) {
            return
        }
        all.forEach { proc ->
            if (!proc.process.isAlive) {
                throw ServerSmokeFail("$step: the ${proc.name} process exited with code ${proc.process.exitValue()}")
            }
        }
        if (System.currentTimeMillis() > deadline) {
            throw ServerSmokeFail("$step: no result after ${timeoutMs / 1000} s")
        }
        Thread.sleep(250)
    }
}

// -PrealGl gives the server smoke client a real OpenGL context in a hidden window.
fun serverSmokeClientArgs(): List<String> = if (project.hasProperty("realGl")) listOf("-PrealGl") else emptyList()

// The pin's own release runs the pin projects.
// Another release of the line runs the version-smoke launch with the pin jar.
// built holds the pins whose jar this run already built.
fun serverSmokeRun(item: TargetVersion, loader: String, built: MutableSet<String>): SmokeBlock {
    val pin = item.pin
    val mc = item.minecraftVersion
    smokeLogs.mkdirs()
    val buildLog = File(smokeLogs, "server-build-$mc.txt")
    val serverTask: List<String>
    val clientTask: List<String>
    // The nil leaf has no runs. Its launches are always the vanilla launch of version-smoke.
    if (mc == pin && loader != "nil") {
        val path = ":mod:$pin:$loader"
        logger.lifecycle("BUILD pin $pin $loader")
        // Build before the launches. The two launches then only read the pin output.
        val build = smokeGradle(listOf("$path:classes", "$path:jar"), buildLog)
        if (build.exit != 0) {
            throw GradleException("Pin $pin build failed. Read $buildLog")
        }
        serverTask = listOf("$path:runServer", "-PserverSmoke")
        clientTask = listOf("$path:runClient", "-PserverSmoke") + serverSmokeClientArgs()
    } else {
        val leaf = smokeLeaf(item, loader)
        if (pin !in built) {
            smokeCopyPinJar(pin, !item.obfuscated, loader)
            built.add(pin)
        }
        val args = listOf("-PversionSmoke", "-Ploader=$loader", "-Pmc_ver=$mc", "-PserverSmoke")
        // Set up the launch once. The two launches then do not set it up at the same time.
        logger.lifecycle("BUILD version-smoke $leaf $mc")
        // The vanilla launch has no classes task: its setup (downloads) runs at configuration.
        val setupTask = if (leaf == "vanilla") "help" else "classes"
        val build = smokeGradle(listOf(":version-smoke:$leaf:$setupTask") + args, buildLog)
        if (build.exit != 0) {
            throw GradleException("version-smoke $leaf for $mc failed. Read $buildLog")
        }
        serverTask = listOf(":version-smoke:$leaf:runServer") + args
        clientTask = listOf(":version-smoke:$leaf:runClient") + args + serverSmokeClientArgs()
    }
    val serverLog = File(smokeLogs, "server-$mc.txt")
    val clientLog = File(smokeLogs, "server-client-$mc.txt")
    val passed = mutableListOf<String>()
    var failure: String? = null
    var server: ServerSmokeProcess? = null
    var client: ServerSmokeProcess? = null
    val outputs = linkedMapOf<String, String>()
    try {
        logger.lifecycle("RUN server $mc pin $pin $loader")
        val srv = ServerSmokeProcess("server", smokeGradleCommand(serverTask), smokeRoot, serverLog)
        server = srv
        serverSmokeAwait("server start", 900_000L, listOf(srv), srv, 0) { it.contains("For help, type \"help\"") }
        passed.add("The dedicated server started.")
        logger.lifecycle("RUN client $mc pin $pin $loader")
        val cli = ServerSmokeProcess("client", smokeGradleCommand(clientTask), smokeRoot, clientLog)
        client = cli
        val both = listOf(srv, cli)
        serverSmokeAwait("client join", 900_000L, both, srv, 0) { it.contains("joined the game") }
        passed.add("The client joined through TCP.")
        // Give the client time to load the world before the scenario.
        Thread.sleep(5000)
        serverScenario.filter { it.loaders == null || loader in it.loaders }.forEach { step ->
            val target = if (step.side == "client") cli else srv
            val mark = if (step.command == null) 0 else target.size()
            step.command?.let { srv.send(it) }
            serverSmokeAwait(step.name, 120_000L, both, target, mark) { it.contains(step.expect) }
            if (step.waitMs > 0) {
                Thread.sleep(step.waitMs)
                serverSmokeAwait("${step.name}: no error after the pause", 1_000L, both, cli, 0) { true }
            }
            passed.add("Step passed: ${step.name}.")
        }
        Thread.sleep(3000)
        serverSmokeAwait("final check", 1_000L, both, cli, 0) { true }
        passed.add("No connection error after the scenario.")
    } catch (error: ServerSmokeFail) {
        failure = error.message
    } finally {
        // The stop below resets the connection. Keep the output from before the stop.
        listOfNotNull(server, client).forEach { proc -> outputs[proc.name] = proc.text() }
        client?.kill()
        val srv = server
        if (srv != null && srv.process.isAlive) {
            try {
                srv.send("stop")
            } catch (_: java.io.IOException) {
                // The process closed its input. The kill below stops it.
            }
            if (!srv.process.waitFor(120, TimeUnit.SECONDS)) {
                srv.kill()
            }
        }
        server?.kill()
    }
    val status = if (failure == null) "passed" else "failed"
    val errors = outputs.flatMap { (name, text) ->
        smokeCollect(text).getValue("error").map { "$name: $it" }
    }
    val block = listOf(
        "## $mc",
        "",
        "Pin `$pin`. Loader `$loader`. Status: $status.",
        "",
        smokeSection("Passed steps", passed),
        smokeSection("Failure", listOfNotNull(failure)),
        smokeSection("Errors", errors.take(80)),
    ).joinToString("\n") + "\n"
    return SmokeBlock(block, status)
}

tasks.register("serverSmoke") {
    group = "verification"
    description = "Start a dedicated server and a headless client of each pin. The client joins and runs the scenario."
    doLast {
        val loader = smokeLoader()
        val pins = smokePropertyList("pin")
        val wanted = smokePropertyList("mc")
        // The default is the pin's own release. -Pmc picks releases. -Pline takes every release of the pin.
        val line = project.hasProperty("line")
        val versions = loadTargetVersions(smokeRoot).filter { item ->
            val pinOk = pins.isEmpty() || pins.contains(item.pin)
            runsOn(item, loader) &&
                pinOk &&
                when {
                    wanted.isNotEmpty() -> wanted.contains(item.minecraftVersion)
                    line -> true
                    else -> item.minecraftVersion == item.pin
                }
        }
        val built = mutableSetOf<String>()
        runReleases(
            "serverSmoke",
            File(smokeOut, "server-$loader.md"),
            listOf(
                "# Server smoke",
                "",
                "Each release starts a dedicated server and a headless $loader client.",
                "The pin's own release runs the pin projects. Another release runs the pin jar in version-smoke.",
                "The client joins through TCP. Then the server console runs the scenario in version-smoke.gradle.kts.",
            ),
            listOf("passed", "failed"),
            byRelease(versions),
        ) { item -> serverSmokeRun(item, loader, built) }
    }
}

// A client for a person on one release of a pin line. It runs the pin jar with a window.
tasks.register("versionPlay") {
    group = "verification"
    description = "Start the pin jar on one release with a window. -Pmc or -Ppin picks the release. -Ploader picks the loader."
    doLast {
        val loader = smokeLoader()
        val wanted = smokePropertyList("mc")
        val pins = smokePropertyList("pin")
        // One -Ppin without -Pmc picks the pin's own release.
        val mc = when {
            wanted.size == 1 -> wanted.single()
            wanted.isEmpty() && pins.size == 1 -> pins.single()
            else -> throw GradleException("versionPlay needs one release: -Pmc=<release> or -Ppin=<pin>.")
        }
        val item = loadTargetVersions(smokeRoot).firstOrNull { it.minecraftVersion == mc }
            ?: throw GradleException("versionProperties has no $mc.")
        if (!runsOn(item, loader)) {
            throw GradleException("$mc does not build for $loader.")
        }
        smokeLogs.mkdirs()
        smokeCopyPinJar(item.pin, !item.obfuscated, loader)
        val leaf = smokeLeaf(item, loader)
        logger.lifecycle("PLAY $mc pin ${item.pin} $loader")
        val command = smokeGradleCommand(
            listOf(":version-smoke:$leaf:runClient", "-PversionSmoke", "-Ploader=$loader", "-Pmc_ver=$mc", "-Pplay") +
                smokeCompatArgs(),
        )
        val process = ProcessBuilder(command).directory(smokeRoot).redirectErrorStream(true).start()
        // Show the game output in this build. The game runs until the person closes it.
        process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> logger.lifecycle(line) }
        val exit = process.waitFor()
        logger.lifecycle("Game directory ${File(smokeRoot, "version-smoke/$leaf/build/client")}")
        if (exit != 0) {
            throw GradleException("The $mc client exited with code $exit.")
        }
    }
}

// Static mixin check. It reads bytecode. No game starts.
val mixinCheckAsm = configurations.create("mixinCheckAsm") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add("mixinCheckAsm", "org.ow2.asm:asm-tree:9.8")

// The vanilla client jar with Mojang names, from the launcher metadata (see MinecraftRelease).
fun mixinCheckGameJar(version: String): File = MinecraftRelease(gradle.gradleUserHomeDir, version).mojangClientJar
fun mixinCheckNeoJar(item: TargetVersion): File? = neoForgePatchedJar(smokeRoot, item)

// The check needs Mojang names. Fabric keeps them in the dev jar before the remap.
fun mixinCheckPinJar(pin: String, loader: String): File {
    smokeLogs.mkdirs()
    val logFile = File(smokeLogs, "check-build-$pin.txt")
    logger.lifecycle("BUILD pin $pin $loader jar")
    val completed = smokeGradle(listOf(":mod:$pin:$loader:jar"), logFile)
    if (completed.exit != 0) {
        throw GradleException("Pin $pin jar failed. Read $logFile")
    }
    val build = File(smokeRoot, "mod/versions/$pin/$loader/build")
    val jars = listOf("devlibs", "libs").map { File(build, it) }.firstNotNullOfOrNull { dir ->
        dir.listFiles()?.filter { file ->
            file.name.startsWith(modId) &&
                file.name.endsWith(".jar") &&
                !file.name.contains("sources") &&
                !file.name.contains("javadoc")
        }?.takeIf { it.isNotEmpty() }
    } ?: throw GradleException("No jar in $build")
    return jars.maxBy { it.lastModified() }
}

tasks.register("mixinCheck") {
    group = "verification"
    description = "Compare the mixins of each pin jar with the Minecraft jar of each release. No game starts."
    doLast {
        requireModLoader("mixinCheck")
        val loader = smokeLoader()
        val versions = smokeSelect(loadTargetVersions(smokeRoot))
        val classpath = mixinCheckAsm.files.joinToString(File.pathSeparator) { it.absolutePath }
        val source = File(smokeRoot, "gradle/mixin-check/MixinCheck.java").absolutePath
        // An empty value means: no gate, no plugin mixins.
        val gate = findProperty("mixin_gate_class")?.toString()?.takeIf { it.isNotBlank() } ?: "-"
        val pluginPackage = findProperty("mixin_plugin_package")?.toString()?.takeIf { it.isNotBlank() } ?: "-"
        // Expected misses with a reason (format in the file header). A clean report has none else.
        val expected = File(smokeRoot, "gradle/mixin-check/expected.txt").takeIf { it.isFile }?.absolutePath ?: "-"
        val failed = mutableListOf<String>()
        versions.groupBy { it.pin }.forEach { (pin, items) ->
            val pinJar = mixinCheckPinJar(pin, loader)
            val report = File(smokeOut, "mixin-check/$pin-$loader.md")
            val pairs = items.map { item ->
                val game = if (loader == "neoforge") mixinCheckNeoJar(item) else mixinCheckGameJar(item.minecraftVersion)
                item.minecraftVersion + "=" + (game?.absolutePath ?: "")
            }
            val command = listOf(
                jdkTool("java").absolutePath,
                "-cp",
                classpath,
                source,
                pinJar.absolutePath,
                report.absolutePath,
                pin,
                loader,
                gate,
                pluginPackage,
                expected,
            ) + pairs
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            // Exit 3: the report has unexpected misses. Other codes: the tool failed.
            when (process.waitFor()) {
                0 -> {}
                3 -> failed.add(pin)
                else -> throw GradleException("MixinCheck failed for pin $pin.\n$text")
            }
            logger.lifecycle("$pin $loader: ${text.trim().lines().last()}")
            logger.lifecycle("Report $report")
        }
        if (failed.isNotEmpty()) {
            throw GradleException("Unexpected mixin misses on pins $failed ($loader). Read build/version-smoke/mixin-check/.")
        }
    }
}
