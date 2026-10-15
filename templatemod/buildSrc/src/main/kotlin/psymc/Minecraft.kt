package psymc

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.net.URI

// Files of one Minecraft release from the launcher metadata (piston-meta).
// Everything is cached in <Gradle user home>/caches/psymc/minecraft/<release>.
class MinecraftRelease(private val gradleUserHome: File, val version: String) {
    val dir = File(gradleUserHome, "caches/psymc/minecraft/$version")

    val json: Map<*, *> by lazy {
        val file = File(dir, "$version.json")
        if (!file.isFile) {
            val manifest = JsonSlurper().parse(URI(MANIFEST).toURL()) as Map<*, *>
            val entry = (manifest["versions"] as List<*>).map { it as Map<*, *> }.firstOrNull { it["id"] == version }
                ?: throw GradleException("The launcher metadata has no Minecraft $version.")
            download(entry["url"].toString(), file)
        }
        JsonSlurper().parse(file) as Map<*, *>
    }

    // The client jar as Mojang ships it. Obfuscated up to 1.21.11.
    val clientJar: File by lazy { downloadEntry("client", "client.jar")!! }

    // The dedicated server jar as Mojang ships it (a bundler jar since 1.18).
    val serverJar: File by lazy { downloadEntry("server", "server.jar")!! }

    // The server jar and its libraries, taken out of the bundler (META-INF/versions, META-INF/libraries),
    // and the main class. A launch with this flat classpath has the game classes in the system class
    // loader, as the client has. The bundler itself would load the game in its own class loader.
    val serverClasspath: Pair<List<File>, String> by lazy {
        val out = File(dir, "server-extracted")
        java.util.zip.ZipFile(serverJar).use { zip ->
            fun entries(list: String): List<String> = zip.getInputStream(zip.getEntry("META-INF/$list.list")).bufferedReader().readLines()
                .filter { it.isNotBlank() }
                .map { it.split('\t')[2] }
            val paths = entries("versions").map { "META-INF/versions/$it" } + entries("libraries").map { "META-INF/libraries/$it" }
            val files = paths.map { path ->
                File(out, path.removePrefix("META-INF/")).also { file ->
                    if (!file.isFile) {
                        file.parentFile.mkdirs()
                        zip.getInputStream(zip.getEntry(path)).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            val main = zip.getInputStream(zip.getEntry("META-INF/main-class")).bufferedReader().readText().trim()
            files to main
        }
    }

    // ProGuard mappings: Mojang names to the obfuscated names. Null on a release without obfuscation.
    val clientMappings: File? by lazy { downloadEntry("client_mappings", "client.txt") }

    // Mojang names to the obfuscated names. Null on a release without obfuscation.
    val mojangToObf: Mappings? by lazy { clientMappings?.let { Mappings.proguard(it) } }

    // The client jar with Mojang names. A named release ships it so. An obfuscated release gets a
    // remapped copy (classes only). The client jar has the server classes too.
    val mojangClientJar: File by lazy {
        val mappings = mojangToObf ?: return@lazy clientJar
        File(dir, "client-mojang.jar").also { jar ->
            if (!jar.isFile) {
                remapGameJar(clientJar, mappings, jar)
            }
        }
    }

    val mainClass: String
        get() = json["mainClass"].toString()

    val assetIndex: String
        get() = (json["assetIndex"] as Map<*, *>)["id"].toString()

    // The Maven coordinates of the libraries. The jars with natives are not in the list.
    fun libraries(): List<String> = (json["libraries"] as List<*>)
        .map { (it as Map<*, *>)["name"].toString() }
        .filter { it.split(":").size == 3 }
        .distinct()

    // The library jars of the launcher for this computer, natives included.
    fun runtimeLibraries(): List<File> {
        val os = currentOs()
        return (json["libraries"] as List<*>).map { it as Map<*, *> }.filter { allowed(it["rules"], os) }.mapNotNull { library ->
            val artifact = ((library["downloads"] as Map<*, *>?)?.get("artifact") as Map<*, *>?) ?: return@mapNotNull null
            val target = File(dir, "libraries/" + artifact["path"])
            if (!target.isFile) {
                download(artifact["url"].toString(), target)
            }
            target
        }
    }

    // An assets directory in the launcher layout (indexes/, objects/) with every object of this release.
    // The ModDevGradle cache has the same layout. Use it when it is complete. Else fill our own
    // shared directory: copy an object from that cache, or download it.
    fun assetsDir(): File {
        val own = File(gradleUserHome, "caches/psymc/assets")
        val neoForm = File(gradleUserHome, "caches/neoformruntime/assets")
        val index = File(own, "indexes/$assetIndex.json")
        if (!index.isFile) {
            download((json["assetIndex"] as Map<*, *>)["url"].toString(), index)
        }
        val hashes = ((JsonSlurper().parse(index) as Map<*, *>)["objects"] as Map<*, *>).values
            .map { (it as Map<*, *>)["hash"].toString() }
            .distinct()
        fun path(hash: String) = "objects/${hash.substring(0, 2)}/$hash"
        if (File(neoForm, "indexes/$assetIndex.json").isFile && hashes.all { File(neoForm, path(it)).isFile }) {
            return neoForm
        }
        val missing = hashes.filter { !File(own, path(it)).isFile }
        if (missing.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                missing.map { hash ->
                    pool.submit {
                        val target = File(own, path(hash))
                        val cached = File(neoForm, path(hash))
                        if (cached.isFile) {
                            target.parentFile.mkdirs()
                            cached.copyTo(target, overwrite = true)
                        } else {
                            download("https://resources.download.minecraft.net/${hash.substring(0, 2)}/$hash", target)
                        }
                    }
                }.forEach { it.get() }
            } finally {
                pool.shutdown()
            }
        }
        return own
    }

    private fun downloadEntry(name: String, fileName: String): File? {
        val entry = (json["downloads"] as Map<*, *>)[name] as Map<*, *>? ?: return null
        val file = File(dir, fileName)
        if (!file.isFile) {
            download(entry["url"].toString(), file)
        }
        return file
    }

    private fun allowed(rules: Any?, os: String): Boolean {
        if (rules !is List<*>) {
            return true
        }
        var allow = false
        rules.map { it as Map<*, *> }.forEach { rule ->
            val ruleOs = (rule["os"] as Map<*, *>?)?.get("name")
            if (ruleOs == null || ruleOs == os) {
                allow = rule["action"] == "allow"
            }
        }
        return allow
    }

    private fun currentOs(): String {
        val name = System.getProperty("os.name").lowercase()
        return when {
            name.contains("win") -> "windows"
            name.contains("mac") -> "osx"
            else -> "linux"
        }
    }

    companion object {
        const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        fun download(url: String, target: File) {
            target.parentFile.mkdirs()
            val temp = File(target.parentFile, target.name + ".part")
            URI(url).toURL().openStream().use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) {
                throw GradleException("Cannot move $temp to $target")
            }
        }
    }
}
