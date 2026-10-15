package psymc

import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

// A Modrinth mod that the mod jar needs beside it at run time.
// The pin compiles against these versions. A release can name other versions in versionProperties:
// library_<slug>_<loader>=<Modrinth version>.
data class Library(
    val slug: String,
    val fabric: String,
    val neoforge: String,
)

// One pin builds one jar per loader. The jar runs on every release of its line.
data class ModPin(
    // The pin id is also the Minecraft release that the pin compiles against.
    val id: String,
    val java: Int,
    val neo: String,
    // Source line: a directory under mod/src that several pins share (rl, id).
    val line: String,
    // The Fabric API build that the Fabric jar compiles against.
    val fabricApi: String,
    val minecraftRange: String,
    val neoRange: String,
    val loaderRange: String,
    // ModDevGradle has no additional runtime classpath from 1.21.10 on. Then use "runtime".
    val lwjglRuntime: String,
    // The game jar is not obfuscated. The Fabric jar needs no remap.
    val namedJar: Boolean = false,
    val libraries: List<Library> = emptyList(),
)

data class TargetVersion(
    val properties: Map<String, String>,
) {
    val minecraftVersion: String
        get() = properties.getValue("minecraft_version")

    val pin: String
        get() = properties.getValue("pin")

    val javaVersion: Int
        get() = properties.getValue("java_version").toInt()

    val obfuscated: Boolean
        get() = properties.getValue("obfuscated") == "true"

    val loaders: Set<String>
        get() = properties.getValue("builds_for").split(",").map { it.trim() }.toSet()

    // The Fabric API build that runs beside the pin jar on this release.
    // A release without fabric_api uses the Fabric API of the pin.
    fun fabricApi(pin: ModPin): String = properties["fabric_api"] ?: pin.fabricApi

    // The library build that runs beside the pin jar on this release.
    // A release without library_<slug>_<loader> uses the build of the pin.
    fun library(library: Library, loader: String): String = properties["library_${library.slug}_$loader"]
        ?: if (loader == "neoforge") library.neoforge else library.fabric
}

// Pins of the mod. Each pin has the project dirs mod/versions/<pin>/<loader>.
// Copy the last entry when a new Minecraft release needs a pin.
// Add a new line directory under mod/src when Minecraft renames a type the mod uses.
val modPins = listOf(
    // ResourceLocation line. Java 21. Releases 1.20.5 through 1.21.1.
    ModPin(
        id = "1.21.1",
        java = 21,
        neo = "21.1.251",
        line = "rl",
        fabricApi = "0.116.17+1.21.1",
        minecraftRange = "[1.20.5,1.21.2)",
        neoRange = "[20.5,21.2)",
        // FML 3 runs 1.20.5 and 1.20.6. FML 4 starts on 1.21.
        loaderRange = "[3,)",
        lwjglRuntime = "additional",
        // A library example. The mod then needs Cloth Config on both loaders.
        // libraries = listOf(Library("cloth-config", "15.0.140+fabric", "15.0.140+neoforge")),
    ),
    // ResourceLocation line. Java 21. Releases 1.21.2 through 1.21.4.
    ModPin(
        id = "1.21.4",
        java = 21,
        neo = "21.4.157",
        line = "rl",
        fabricApi = "0.119.4+1.21.4",
        minecraftRange = "[1.21.2,1.21.5)",
        neoRange = "[21.2,21.5)",
        loaderRange = "[4,)",
        lwjglRuntime = "additional",
    ),
    // ResourceLocation line. Java 21. Releases 1.21.5 through 1.21.10.
    ModPin(
        id = "1.21.10",
        java = 21,
        neo = "21.10.64",
        line = "rl",
        fabricApi = "0.138.4+1.21.10",
        minecraftRange = "[1.21.5,1.21.11)",
        neoRange = "[21.5,21.11)",
        loaderRange = "[4,)",
        lwjglRuntime = "runtime",
    ),
    // Identifier line. The last obfuscated release.
    ModPin(
        id = "1.21.11",
        java = 21,
        neo = "21.11.45",
        line = "id",
        fabricApi = "0.141.6+1.21.11",
        minecraftRange = "[1.21.11,1.21.12)",
        neoRange = "[21.11,)",
        loaderRange = "[3,)",
        lwjglRuntime = "runtime",
    ),
    // Identifier line. Java 25. The game jar is not obfuscated. Releases 26.1 through 26.3.
    ModPin(
        id = "26.2",
        java = 25,
        neo = "26.2.0.68",
        line = "id",
        fabricApi = "0.161.0+26.2",
        minecraftRange = "[26.1,26.4)",
        neoRange = "[26.1,26.4)",
        loaderRange = "[3,)",
        lwjglRuntime = "runtime",
        namedJar = true,
    ),
)

// Fabric reads a predicate. NeoForge reads the Maven range.
fun fabricMinecraftPredicate(mavenRange: String): String {
    val matcher = Regex("""^\[([^,\]]+),([^,\]]+)\)$""").matchEntire(mavenRange) ?: return mavenRange
    return ">=" + matcher.groupValues[1] + " <" + matcher.groupValues[2]
}

fun compareMinecraftVersions(left: String, right: String): Int {
    val a = left.split(".")
    val b = right.split(".")
    val count = minOf(a.size, b.size)
    for (index in 0 until count) {
        val av = a[index].toIntOrNull()
        val bv = b[index].toIntOrNull()
        val compared = when {
            av != null && bv != null -> av.compareTo(bv)
            else -> a[index].compareTo(b[index])
        }
        if (compared != 0) {
            return compared
        }
    }
    return a.size.compareTo(b.size)
}

fun loadTargetVersions(root: File): List<TargetVersion> {
    val dir = File(root, "versionProperties")
    if (!dir.isDirectory) {
        throw GradleException("Missing $dir")
    }
    val files = dir.listFiles { child -> child.isFile && child.name.endsWith(".properties") }?.toList().orEmpty()
    return files.sortedWith { left, right ->
        compareMinecraftVersions(
            left.name.removeSuffix(".properties"),
            right.name.removeSuffix(".properties"),
        )
    }.map { child ->
        val props = Properties()
        child.inputStream().use { props.load(it) }
        val map = props.stringPropertyNames().associateWith { key -> props.getProperty(key).trim() }
        TargetVersion(map)
    }
}
