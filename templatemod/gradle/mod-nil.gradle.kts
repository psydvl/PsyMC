import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.jvm.tasks.ProcessResources
import psymc.GameHierarchy
import psymc.Mappings
import psymc.MinecraftRelease
import psymc.NilTarget
import psymc.loadTargetVersions
import psymc.modPin
import psymc.requiredProperty
import psymc.sourceSets
import psymc.writeNilMappings
import java.io.File
import java.util.zip.ZipFile

// NilLoader pin leaf: a plain Java project. NilLoader loads the jar as a nilmod.
// NilLoader has no Mixin. The leaf drops the mixin and mixinplugin packages and the mixin configs;
// Mini patches in src/nil do the same work.
// The code compiles against the game jar with Mojang names. On an obfuscated pin, the jar gets
// META-INF/nil/mappings.json, and NilLoader remaps the mod classes at run time:
// - <release>: Mojang names to the obfuscated names of that release (the vanilla game).
// - default: the same for the pin release.
// - intermediary: Mojang names to the Fabric intermediary names (NilLoader beside Fabric Loader).
// - mojmap: no change (NilLoader beside NeoForge).
// The nilmod entry selects one of them.

apply(from = layout.settingsDirectory.file("gradle/mod-common.gradle.kts").asFile.path)

val pin = modPin()
val modId = requiredProperty("mod_id")
val release = MinecraftRelease(gradle.gradleUserHomeDir, pin.id)

sourceSets().getByName("main").java.exclude("**/mixin/**", "**/mixinplugin/**")
sourceSets().getByName("main").resources.exclude("*.mixins.json")

repositories {
    maven {
        name = "Sleeping Town"
        url = uri("https://repo.sleeping.town")
        content {
            includeGroup("com.unascribed")
        }
    }
    maven {
        name = "Minecraft libraries"
        url = uri("https://libraries.minecraft.net")
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
        content {
            includeGroup("net.fabricmc")
        }
    }
}

// The game jar with Mojang names (see MinecraftRelease.mojangClientJar).
val mojangToObf: Mappings? = release.mojangToObf
val namedGameJar: File = release.mojangClientJar

dependencies {
    add("compileOnly", "com.unascribed:nilloader:${requiredProperty("nilloader_version")}")
    add("compileOnly", files(namedGameJar))
    release.libraries().forEach { add("compileOnly", it) }
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", version)
    filesMatching("$modId.nilmod.css") {
        expand("version" to version)
    }
}

if (mojangToObf != null) {
    // The obfuscated names differ on each release. The jar gets one mapping set per release of the
    // pin line, by release id. The nilmod entry reads the release from version.json of the game.
    val lineReleases = loadTargetVersions(layout.settingsDirectory.asFile)
        .filter { it.pin == pin.id && it.obfuscated }
        .map { it.minecraftVersion }
    val intermediary = configurations.detachedConfiguration(dependencies.create("net.fabricmc:intermediary:${pin.id}:v2"))
    val mappingsDir = layout.buildDirectory.dir("nil-mappings")
    val nilMappings = tasks.register("nilMappings") {
        description = "Write META-INF/nil/mappings.json for the compiled mod classes."
        val classes = tasks.named<JavaCompile>("compileJava").flatMap { it.destinationDirectory }
        inputs.dir(classes)
        inputs.property("releases", lineReleases)
        outputs.dir(mappingsDir)
        doLast {
            val named = GameHierarchy(namedGameJar)
            val tiny = ZipFile(intermediary.singleFile).use { zip ->
                zip.getInputStream(zip.getEntry("mappings/mappings.tiny")).bufferedReader().readText()
            }
            val targets = linkedMapOf(
                "default" to NilTarget(mojangToObf, named::parents),
                "intermediary" to NilTarget(mojangToObf.then(Mappings.tinyV2(tiny), GameHierarchy(release.clientJar)::parents), named::parents),
                "mojmap" to NilTarget(Mappings(emptyMap())) { emptyList() },
            )
            lineReleases.forEach { id ->
                val other = MinecraftRelease(gradle.gradleUserHomeDir, id)
                val mappings = Mappings.proguard(other.clientMappings!!)
                val toMojang = mappings.classes.values.associate { it.target to it.source }
                val hierarchy = GameHierarchy(other.clientJar) { toMojang[it] ?: it }
                targets[id] = NilTarget(mappings, hierarchy::parents)
            }
            val out = mappingsDir.get().file("META-INF/nil/mappings.json").asFile
            writeNilMappings(classes.get().asFile, targets, out)
        }
    }
    tasks.named<Jar>("jar") {
        from(nilMappings)
    }
}
