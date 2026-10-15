import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = "NeoForged"
            url = uri("https://maven.neoforged.net/releases")
        }
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
    // Plugin versions for all projects. The leaf build files name the plugins without a version.
    plugins {
        id("net.fabricmc.fabric-loom") version "1.17.20"
        id("net.fabricmc.fabric-loom-remap") version "1.17.20"
        id("net.neoforged.moddev") version "2.0.147"
        id("net.ltgt.errorprone") version "4.1.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = providers.gradleProperty("mod_id").get()

// The loaders of the mod (gradle.properties: loaders).
val loaders = providers.gradleProperty("loaders").get().split(",").map { it.trim() }.filter { it.isNotEmpty() }

// The pins are the directories mod/versions/<pin>. Versions.kt describes each pin.
val pinIds = file("mod/versions").listFiles()
    ?.filter { dir -> loaders.any { File(dir, "$it/build.gradle.kts").isFile } }
    ?.map { it.name }
    .orEmpty()

fun compareMinecraftVersions(left: String, right: String): Int {
    val a = left.split(".")
    val b = right.split(".")
    for (index in 0 until minOf(a.size, b.size)) {
        val av = a[index].toIntOrNull()
        val bv = b[index].toIntOrNull()
        val compared = if (av != null && bv != null) av.compareTo(bv) else a[index].compareTo(b[index])
        if (compared != 0) {
            return compared
        }
    }
    return a.size.compareTo(b.size)
}

// A version-smoke launch runs a built pin jar on one release. The root smoke tasks start it
// as a nested Gradle run with -PversionSmoke -Pmc_ver=<release> -Ploader=<loader>.
val startArgs = gradle.startParameter.projectProperties
if (startArgs.containsKey("versionSmoke")) {
    val mc = startArgs["mc_ver"] ?: throw GradleException("Pass -Pmc_ver with -PversionSmoke.")
    val target = file("versionProperties/$mc.properties")
    if (!target.isFile) {
        throw GradleException("versionProperties has no $mc.")
    }
    val props = Properties()
    target.inputStream().use { props.load(it) }
    val selected = props.stringPropertyNames().associateWith { key -> props.getProperty(key).trim() }
    gradle.extra["selectedTarget"] = selected
    val leaf = when (startArgs["loader"] ?: "fabric") {
        "neoforge" -> "neoforge"
        "nil" -> "vanilla"
        "fabric" -> if (selected.getValue("obfuscated") == "true") "obfuscated" else "named"
        else -> throw GradleException("loader must be fabric, neoforge, or nil.")
    }
    include(":version-smoke:$leaf")
    project(":version-smoke:$leaf").projectDir = file("version-smoke/$leaf")
    if (startArgs.containsKey("mixinAudit")) {
        include(":version-smoke:mixin-audit")
        project(":version-smoke:mixin-audit").projectDir = file("version-smoke/mixin-audit")
    }
}

// Each pin project sets up Minecraft when Gradle configures it. That takes time.
// Include only the pin projects that the requested tasks need:
// - A task path such as :mod:1.21.4:fabric:runClient selects that pin and loader.
// - A root task that starts nested Gradle runs needs no pin project. The nested runs select theirs.
// - -Ppins=1.21.4,26.2 selects these pins on all loaders.
// - nilCheck selects the nil leaves (of -Ppin, else of all pins).
// - Any other task (an aggregate, an unknown name) keeps all projects.
val rootOnlyTasks = setOf(
    "versionSmoke", "mixinAudit", "mixinCheck", "serverSmoke", "versionPlay",
    "spotlessApply", "spotlessCheck", "pins", "help", "tasks", "mcInspect", "mcLambdas", "lastRun", "makeTestWorld",
)
// Each entry is pin, loader. Null means all leaves.
val selectedLeaves: Set<List<String>>? = run {
    val pinsArg = startArgs["pins"]
    if (pinsArg != null) {
        val pins = pinsArg.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return@run pins.flatMap { pin -> loaders.map { listOf(pin, it) } }.toSet()
    }
    val leaves = mutableSetOf<List<String>>()
    val pathPattern = Regex("^:mod:([^:]+):([^:]+):.+$")
    for (task in gradle.startParameter.taskNames) {
        if (task.startsWith("-")) {
            continue
        }
        val match = pathPattern.find(task)
        when {
            match != null -> leaves.add(match.groupValues.drop(1))
            task.startsWith(":version-smoke:") -> {}
            task.removePrefix(":") in rootOnlyTasks -> {}
            // nilCheck builds the nil jars: the nil leaves of all pins, or of -Ppin.
            task.removePrefix(":") == "nilCheck" -> {
                val only = startArgs["pin"]?.split(",")?.map { it.trim() }?.toSet()
                pinIds.filter { only == null || it in only }.forEach { leaves.add(listOf(it, "nil")) }
            }
            else -> return@run null
        }
    }
    leaves
}

pinIds.sortedWith(::compareMinecraftVersions).forEach { pin ->
    val pinLoaders = loaders.filter { loader ->
        file("mod/versions/$pin/$loader/build.gradle.kts").isFile &&
            (selectedLeaves == null || listOf(pin, loader) in selectedLeaves)
    }
    if (pinLoaders.isEmpty()) {
        return@forEach
    }
    include(":mod:$pin")
    project(":mod").projectDir = file("mod")
    project(":mod:$pin").projectDir = file("mod/versions/$pin")
    pinLoaders.forEach { loader ->
        include(":mod:$pin:$loader")
        project(":mod:$pin:$loader").projectDir = file("mod/versions/$pin/$loader")
    }
}
