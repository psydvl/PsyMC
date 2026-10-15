import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import psymc.modPins
import java.io.File

// Merge the NeoForge jar and the Fabric jar of each pin into one jar.
// The Forgix plugin keeps one merge on the root project, so this script calls the library.
buildscript {
    repositories {
        gradlePluginPortal()
    }
    dependencies {
        classpath("io.github.pacifistmc.forgix:Forgix:2.0.0")
    }
}

val forgixMerge = Class.forName("io.github.pacifistmc.forgix.Forgix")
    .declaredMethods
    .first { method -> method.name == "mergeLoaders" && method.parameterTypes.size == 3 }

val modId = property("mod_id").toString()

// Settings includes only the pin projects that the tasks need. Merge the pins it included.
val mergeTasks = modPins.filter { spec ->
    findProject(":mod:${spec.id}:fabric") != null && findProject(":mod:${spec.id}:neoforge") != null
}.map { spec ->
    val pin = spec.id
    // A named pin has no remap task. The jar is already named.
    val fabricJarTask = if (spec.namedJar) "jar" else "remapJar"
    val output = layout.settingsDirectory.file("mod/versions/$pin/build/forgix/$modId-$pin.jar")
    tasks.register("merge${pin.replace(".", "_")}") {
        group = "build"
        description = "Merge the $pin NeoForge jar and Fabric jar."
        dependsOn(":mod:$pin:fabric:$fabricJarTask", ":mod:$pin:neoforge:jar")
        outputs.file(output)
        doLast {
            val fabricFile = project(":mod:$pin:fabric")
                .tasks.named<AbstractArchiveTask>(fabricJarTask).get().archiveFile.get().asFile
            val neoFile = project(":mod:$pin:neoforge")
                .tasks.named<AbstractArchiveTask>("jar").get().archiveFile.get().asFile
            val dest = output.asFile
            dest.parentFile.mkdirs()
            val jars = linkedMapOf<File, String>()
            jars[neoFile] = "neoforge"
            jars[fabricFile] = "fabric"
            forgixMerge.invoke(null, jars, dest, true)
            logger.lifecycle("Merged jar $dest")
        }
    }
}

tasks.register("mergeJars") {
    group = "build"
    description = "Merge the NeoForge jar and the Fabric jar of every pin."
    dependsOn(mergeTasks)
}
