import org.gradle.api.GradleException
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.named
import psymc.MinecraftRelease
import psymc.NilRelease
import psymc.loadTargetVersions
import psymc.modPins
import psymc.nilCheck
import java.io.File

// NilLoader only. Delete this file together with the nil leaves.
// nilCheck: the Mini patch targets and the game references of each nil jar against the game jar
// (Mojang names) of each release of the pin line, and the mapping set of each obfuscated release.
// No game starts. -Ppin=<pin>[,...] and -Pmc=<release>[,...] narrow it. Fails on any problem.

fun nilCheckList(name: String): Set<String> = gradle.startParameter.projectProperties[name]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

val nilCheckPins = modPins.filter { spec ->
    findProject(":mod:${spec.id}:nil") != null && (nilCheckList("pin").isEmpty() || spec.id in nilCheckList("pin"))
}

tasks.register("nilCheck") {
    group = "verification"
    description = "Compare the nil patches and game references with the game jar of each release. No game starts."
    dependsOn(nilCheckPins.map { ":mod:${it.id}:nil:jar" })
    doLast {
        val wanted = nilCheckList("mc")
        val failed = mutableListOf<String>()
        nilCheckPins.forEach { spec ->
            val jar = project(":mod:${spec.id}:nil").tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val releases = loadTargetVersions(rootProject.projectDir)
                .filter { it.pin == spec.id && (wanted.isEmpty() || it.minecraftVersion in wanted) }
                .map { item ->
                    val release = MinecraftRelease(gradle.gradleUserHomeDir, item.minecraftVersion)
                    NilRelease(item.minecraftVersion, release.mojangClientJar, release.mojangToObf)
                }
            val report = File(layout.buildDirectory.get().asFile, "version-smoke/nil-check/${spec.id}.md")
            val problems = nilCheck(jar, spec.id, releases, report)
            logger.lifecycle("${spec.id} nil: $problems problem(s) on ${releases.size} release(s). Report $report")
            if (problems > 0) {
                failed.add(spec.id)
            }
        }
        if (failed.isNotEmpty()) {
            throw GradleException("nilCheck found problems on pins $failed. Read build/version-smoke/nil-check/.")
        }
    }
}
