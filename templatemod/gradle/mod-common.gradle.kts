import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import psymc.archivesName
import psymc.javaLanguage
import psymc.layers
import psymc.modPin
import psymc.requiredProperty
import psymc.sourceSets
import psymc.useErrorProne

// The part of a pin leaf that does not depend on the loader.
// The project is :mod:<pin>:<loader>. The loader scripts apply this script first.

val pin = modPin()
val loader = project.name
val src = layout.settingsDirectory.dir("mod/src").asFile

version = requiredProperty("mod_version")
group = requiredProperty("mod_group_id")
archivesName("${requiredProperty("mod_id")}-$loader-${pin.id}")
javaLanguage(pin.java)

// Source layers, lowest first. A file in a higher layer replaces the file with the same path.
// common, then the line (rl, id), then the pin, then the same three for the loader.
val layerNames = listOf(
    "common",
    pin.line,
    pin.id,
    loader,
    "$loader/${pin.line}",
    "$loader/${pin.id}",
)
sourceSets().getByName("main").java.layers(layerNames.map { src.resolve("$it/java") })
sourceSets().getByName("main").resources.layers(layerNames.map { src.resolve("$it/resources") })
sourceSets().getByName("test").java.setSrcDirs(listOf(src.resolve("test/java")))

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    add("errorprone", "com.google.errorprone:error_prone_core:2.36.0")
    add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:cast",
            "-Xlint:deprecation",
            "-Xlint:divzero",
            "-Xlint:fallthrough",
            "-Xlint:overrides",
            "-Xlint:rawtypes",
            "-Xlint:static",
            "-Xlint:unchecked",
            "-Xlint:-processing",
            "-Xlint:-serial",
        ),
    )
    useErrorProne(
        "EqualsNull",
        "InfiniteRecursion",
        "FutureReturnValueIgnored",
        "SelfEquals",
        "ArrayEquals",
        "EqualsIncompatibleType",
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
