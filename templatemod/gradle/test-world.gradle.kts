import org.gradle.api.GradleException
import psymc.MinecraftRelease
import psymc.NbtTag
import psymc.compound
import psymc.jdkTool
import psymc.readNbtFile
import psymc.writeNbtFile
import java.io.File
import java.util.concurrent.TimeUnit

// makeTestWorld: make the prepared world testworlds/<name> for -PtestWorld.
// A vanilla dedicated server of -Pmc (default 1.20.5, the oldest release: a world opens on its own
// release and on later releases only) makes a superflat world. The console sets the game rules and
// puts a few blocks at the spawn. Then level.dat gets creative mode and commands for singleplayer.
// -Pname=<name> (default flat). The old testworlds/<name> is replaced.

// Console commands after the server start. Change them for your checks.
val testWorldCommands = listOf(
    "gamerule doDaylightCycle false",
    "gamerule doWeatherCycle false",
    "gamerule doMobSpawning false",
    "gamerule doTraderSpawning false",
    "gamerule doPatrolSpawning false",
    "gamerule doInsomnia false",
    "time set noon",
    "weather clear",
    "setworldspawn 0 -60 0",
    "fill -4 -61 -4 4 -61 4 minecraft:smooth_stone",
    "setblock 2 -60 0 minecraft:chest",
    "setblock 2 -60 1 minecraft:crafting_table",
    "save-all flush",
)

// The first saved hotbar (creative inventory, load with X+1). The rest stay empty.
// testworlds/hotbar.nbt; -PtestWorld puts it into the client.
val testHotbar = listOf(
    "${property("mod_id")}:probe_block",
    "minecraft:debug_stick",
    "minecraft:command_block",
    "minecraft:structure_block",
    "minecraft:barrier",
    "minecraft:light",
    "minecraft:stone",
    "minecraft:chest",
    "minecraft:crafting_table",
)

tasks.register("makeTestWorld") {
    group = "help"
    description = "Make testworlds/<name> with a vanilla server. -Pmc (default 1.20.5), -Pname (default flat)."
    doLast {
        // Read from the command line: findProperty("name") gives the name of the project.
        val args = gradle.startParameter.projectProperties
        val mc = args["mc"] ?: "1.20.5"
        val name = args["name"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: "flat"
        val release = MinecraftRelease(gradle.gradleUserHomeDir, mc)
        val dir = File(layout.buildDirectory.get().asFile, "test-world/$name-$mc")
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "eula.txt").writeText("eula=true\n")
        File(dir, "server.properties").writeText(
            listOf(
                "level-name=$name",
                "level-type=minecraft\\:flat",
                "level-seed=psymc",
                "generate-structures=false",
                "gamemode=creative",
                "difficulty=peaceful",
                "spawn-monsters=false",
                "spawn-protection=0",
                "online-mode=false",
                "server-port=25590",
                "",
            ).joinToString("\n"),
        )
        logger.lifecycle("Start the Minecraft $mc server in $dir")
        val process = ProcessBuilder(jdkTool("java").absolutePath, "-jar", release.serverJar.absolutePath, "nogui")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pump = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                lines.add(line)
                println(line)
            }
        }
        pump.isDaemon = true
        pump.start()
        fun send(command: String) {
            println("CONSOLE> $command")
            process.outputStream.write((command + "\n").toByteArray())
            process.outputStream.flush()
        }
        val deadline = System.currentTimeMillis() + 300_000L
        while (synchronized(lines) { lines.none { it.contains("Done (") } }) {
            if (!process.isAlive || System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                throw GradleException("The server did not start. Read $dir/logs/latest.log")
            }
            Thread.sleep(250)
        }
        testWorldCommands.forEach { command ->
            send(command)
            Thread.sleep(300)
        }
        Thread.sleep(2000)
        send("stop")
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GradleException("The server did not stop. Read $dir/logs/latest.log")
        }
        val world = File(dir, name)
        // Singleplayer reads the game mode and the cheat flag from level.dat.
        val levelDat = File(world, "level.dat")
        val (rootName, root) = readNbtFile(levelDat)
        val data = root.compound().getValue("Data").compound()
        data["GameType"] = NbtTag(3, 1)
        data["allowCommands"] = NbtTag(1, 1.toByte())
        data["Difficulty"] = NbtTag(1, 0.toByte())
        writeNbtFile(levelDat, rootName, root)
        listOf("session.lock", "level.dat_old").forEach { File(world, it).delete() }
        listOf("playerdata", "stats", "advancements").forEach { File(world, it).deleteRecursively() }
        val target = File(rootProject.projectDir, "testworlds/$name")
        target.deleteRecursively()
        world.copyRecursively(target)
        // The hotbar file has the data version of this release. A later release upgrades it.
        val versionJson = java.util.zip.ZipFile(release.clientJar).use { zip ->
            zip.getInputStream(zip.getEntry("version.json")).bufferedReader().readText()
        }
        val dataVersion = Regex("\"world_version\"\\s*:\\s*(\\d+)").find(versionJson)?.groupValues?.get(1)?.toInt()
            ?: throw GradleException("No world_version in version.json of $mc.")
        fun stack(id: String?): NbtTag = NbtTag(
            10,
            if (id == null) linkedMapOf() else linkedMapOf("id" to NbtTag(8, id), "count" to NbtTag(3, 1)),
        )
        val hotbar = linkedMapOf<String, NbtTag>("DataVersion" to NbtTag(3, dataVersion))
        for (row in 0 until 9) {
            val items = (0 until 9).map { slot -> stack(if (row == 0) testHotbar.getOrNull(slot) else null) }
            hotbar["$row"] = NbtTag(9, 10 to items.toMutableList())
        }
        val hotbarFile = File(rootProject.projectDir, "testworlds/hotbar.nbt")
        writeNbtFile(hotbarFile, "", NbtTag(10, hotbar), gzip = false)
        logger.lifecycle("Test world $target ($mc). Use -PtestWorld=$name. Saved hotbar $hotbarFile.")
    }
}
