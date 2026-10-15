import java.io.File

// What failed in the last launch: the newest crash report and the error lines of latest.log of
// each game directory, without known noise. No game starts.
// -Ppin=<pin> and -Ploader=<loader> select the game directories of one pin project
// (mod/versions/<pin>/<loader>/build/*). Without them: every game directory of the pin projects
// and of the version-smoke launches. The newest log comes first.

// Lines that each offline dev launch prints. They are not the cause of a failure.
val lastRunNoise = listOf(
    "realms", "authlib", "YggdrasilAuthentication", "Failed to fetch user properties", "SignedJWT",
    "Status: 401", "MinecraftClientHttpException", "InvalidCredentialsException",
    "data fixer", "Datafixer", "PerfOS", "Ambiguity between arguments", "Error starting SoundSystem",
    "OpenAL 1.1 not supported", "Failed to open pack", "assets/objects",
    // The flat world of the server smoke has no layer settings.
    "No key layers",
)

fun lastRunGameDirs(): List<File> {
    val root = rootProject.projectDir
    val pin = findProperty("pin")?.toString()
    val loader = findProperty("loader")?.toString()
    val buildDirs = if (pin != null || loader != null) {
        File(root, "mod/versions").listFiles().orEmpty()
            .filter { pin == null || it.name == pin }
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { loader == null || it.name == loader }
            .map { File(it, "build") }
    } else {
        File(root, "mod/versions").listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }.map { File(it, "build") } +
            File(root, "version-smoke").listFiles().orEmpty().map { File(it, "build") }
    }
    return buildDirs.flatMap { it.listFiles().orEmpty().toList() }.filter { File(it, "logs/latest.log").isFile }
}

tasks.register("lastRun") {
    group = "help"
    description = "Print the newest crash report and the error lines of the last launch. -Ppin and -Ploader narrow it."
    doLast {
        val dirs = lastRunGameDirs().sortedByDescending { File(it, "logs/latest.log").lastModified() }
        if (dirs.isEmpty()) {
            println("No game directory with logs/latest.log.")
            return@doLast
        }
        val text = StringBuilder("# Last run\n")
        dirs.forEach { dir ->
            val log = File(dir, "logs/latest.log")
            text.append("\n## ${dir.relativeTo(rootProject.projectDir)}\n\nLog `${log.relativeTo(rootProject.projectDir)}`, ")
                .append(java.time.Instant.ofEpochMilli(log.lastModified())).append(".\n\n")
            val crash = File(dir, "crash-reports").listFiles().orEmpty().filter { it.isFile }.maxByOrNull { it.lastModified() }
            // A crash report older than the log belongs to an earlier launch.
            if (crash != null && crash.lastModified() >= log.lastModified() - 60_000) {
                text.append("### Crash report `${crash.name}`\n\n```\n")
                crash.readLines().takeWhile { !it.startsWith("-- ") }.take(40).forEach { text.append(it).append('\n') }
                text.append("```\n\n")
            }
            val lines = log.readLines()
            val errors = lines.indices.filter { index ->
                val line = lines[index]
                (line.contains("/ERROR]") || line.contains("Exception") || line.contains("Caused by")) &&
                    lastRunNoise.none { line.contains(it, ignoreCase = true) }
            }
            text.append("### Errors\n\n")
            if (errors.isEmpty()) {
                text.append("None.\n")
            } else {
                text.append("```\n")
                errors.take(60).forEach { index ->
                    text.append(lines[index]).append('\n')
                    // The first frames show where it happened.
                    lines.drop(index + 1).take(3).filter { it.trimStart().startsWith("at ") }.forEach { text.append(it).append('\n') }
                }
                text.append("```\n")
            }
        }
        val out = File(layout.buildDirectory.get().asFile, "version-smoke/last-run.md")
        out.parentFile.mkdirs()
        out.writeText(text.toString())
        println(text)
        logger.lifecycle("Report $out")
    }
}
