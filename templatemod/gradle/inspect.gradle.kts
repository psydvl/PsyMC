import org.gradle.api.GradleException
import psymc.MinecraftRelease
import psymc.TargetVersion
import psymc.compareMinecraftVersions
import psymc.describeMethods
import psymc.lambdasOf
import psymc.loadTargetVersions
import psymc.matchMethods
import psymc.neoForgePatchedJar
import psymc.readGameClass
import java.io.File

// Look at the game code on many releases at once. No game starts.
// - mcInspect: the signatures, the calls, and (with -Pbytecode) the bytecode of a method.
// - mcLambdas: the synthetic lambdas of a method: the names for a mixin method selector.
// Properties: -Pclass=<Mojang class> -Pmethod=<name or name(desc)>.
// Releases: -Pmc=<release>[,...], or -Ppin=<pin>[,...] for the pin lines, else every release.
// -Ploader=vanilla (default; the client jar with Mojang names) or neoforge (the patched jar of
// ModDevGradle: a release appears after one NeoForge build or smoke on it). mcLambdas shows both.

val inspectRoot = rootProject.projectDir
val inspectOut = File(layout.buildDirectory.get().asFile, "version-smoke/inspect")

fun inspectList(name: String): Set<String> = findProperty(name)?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

fun inspectReleases(): List<TargetVersion> {
    val mc = inspectList("mc")
    val pins = inspectList("pin")
    return loadTargetVersions(inspectRoot).filter { item ->
        when {
            mc.isNotEmpty() -> item.minecraftVersion in mc
            pins.isNotEmpty() -> item.pin in pins
            else -> true
        }
    }.sortedWith { left, right -> compareMinecraftVersions(left.minecraftVersion, right.minecraftVersion) }
}

// Read -P<name> from the command line. findProperty("class") gives the class of the project.
fun inspectRequired(name: String): String = gradle.startParameter.projectProperties[name]?.takeIf { it.isNotBlank() } ?: throw GradleException("Pass -P$name.")

// The game jar of one release for one loader, or null with the reason.
fun inspectJar(item: TargetVersion, loader: String): Pair<File?, String> = when (loader) {
    "neoforge" -> neoForgePatchedJar(inspectRoot, item)?.let { it to "" }
        ?: (null to "no NeoForge jar yet; run a NeoForge build or smoke on this release once")
    else -> MinecraftRelease(gradle.gradleUserHomeDir, item.minecraftVersion).mojangClientJar to ""
}

tasks.register("mcInspect") {
    group = "help"
    description = "Print the signatures and calls of a game method on each release. -Pclass, -Pmethod, -Pmc or -Ppin, -Ploader, -Pbytecode."
    doLast {
        val owner = inspectRequired("class")
        val method = inspectRequired("method")
        val loader = if (findProperty("loader")?.toString() == "neoforge") "neoforge" else "vanilla"
        val text = StringBuilder("# $owner $method ($loader)\n")
        inspectReleases().forEach { item ->
            text.append("\n## ${item.minecraftVersion} (pin ${item.pin})\n\n")
            val (jar, reason) = inspectJar(item, loader)
            val node = jar?.let { readGameClass(it, owner) }
            when {
                jar == null -> text.append("- Skipped: $reason.\n")
                node == null -> text.append("- The class is not in this release.\n")
                else -> text.append(describeMethods(node, matchMethods(node, method), project.hasProperty("bytecode")))
            }
        }
        val out = File(inspectOut, "${owner.substringAfterLast('.')}.${method.substringBefore('(').replace("<", "").replace(">", "")}-$loader.md")
        out.parentFile.mkdirs()
        out.writeText(text.toString())
        println(text)
        logger.lifecycle("Report $out")
    }
}

tasks.register("mcLambdas") {
    group = "help"
    description = "List the synthetic lambdas of a game method on each release, vanilla and NeoForge. -Pclass, -Pmethod, -Pmc or -Ppin."
    doLast {
        val owner = inspectRequired("class")
        val method = inspectRequired("method")
        val loaders = findProperty("loader")?.toString()?.let { listOf(if (it == "neoforge") "neoforge" else "vanilla") }
            ?: listOf("vanilla", "neoforge")
        val text = StringBuilder("# Lambdas of $owner $method\n\n| Release | Loader | Lambdas |\n| --- | --- | --- |\n")
        val all = linkedSetOf<String>()
        inspectReleases().forEach { item ->
            loaders.forEach { loader ->
                val (jar, reason) = inspectJar(item, loader)
                val node = jar?.let { readGameClass(it, owner) }
                val cell = when {
                    jar == null -> reason
                    node == null -> "no such class"
                    else -> lambdasOf(node, matchMethods(node, method)).also { all.addAll(it) }.joinToString(", ").ifEmpty { "none" }
                }
                text.append("| ${item.minecraftVersion} | $loader | $cell |\n")
            }
        }
        // Fabric gets the intermediary name from the Mojang name at remapJar. NeoForge needs its own names.
        text.append("\nAll names for a method selector:\n\n```java\nmethod = {")
            .append(all.joinToString(", ") { "\"$it\"" }).append("}\n```\n")
        val out = File(inspectOut, "${owner.substringAfterLast('.')}.${method.substringBefore('(').replace("<", "").replace(">", "")}-lambdas.md")
        out.parentFile.mkdirs()
        out.writeText(text.toString())
        println(text)
        logger.lifecycle("Report $out")
    }
}
