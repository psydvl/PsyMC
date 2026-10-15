import psymc.modPins

plugins {
    id("com.diffplug.spotless") version "8.1.0"
}

repositories {
    mavenCentral()
}

// The format targets are lists of files. A directory target would overlap the build outputs of
// the pin projects, and Gradle then reports an implicit dependency.
fun sourceFiles(vararg patterns: String) = files(
    fileTree(projectDir) {
        include(*patterns)
        exclude("**/build/**", "**/.gradle/**", ".local/**", "**/run/**")
    }.files,
)

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target(sourceFiles("mod/src/**/*.java", "version-smoke/*/src/**/*.java", "gradle/**/*.java"))
        googleJavaFormat("1.28.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target(sourceFiles("*.gradle.kts", "gradle/*.gradle.kts", "buildSrc/*.gradle.kts", "mod/versions/*/*/build.gradle.kts", "version-smoke/*/build.gradle.kts"))
        ktlint("1.5.0")
    }
    kotlin {
        target(sourceFiles("buildSrc/src/**/*.kt"))
        ktlint("1.5.0")
    }
    format("misc") {
        target(sourceFiles("*.md", "docs/**/*.md", ".gitignore", ".gitattributes", ".editorconfig", "versionProperties/*.properties"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Settings includes only the pin projects that the tasks need. Compile the included ones.
val leafPaths = subprojects.map { it.path }.filter { it.startsWith(":mod:") && it.count { c -> c == ':' } == 3 }

tasks.register("compileAll") {
    group = "build"
    description = "Compile every pin on every loader."
    dependsOn(leafPaths.map { "$it:compileJava" })
}

tasks.register("pins") {
    group = "help"
    description = "Print the pins of the mod."
    doLast {
        modPins.forEach { spec ->
            println("${spec.id}  java ${spec.java}  line ${spec.line}  minecraft ${spec.minecraftRange}  named ${spec.namedJar}")
        }
    }
}

apply(from = "gradle/forgix.gradle.kts")
apply(from = "gradle/version-smoke.gradle.kts")
apply(from = "gradle/inspect.gradle.kts")
apply(from = "gradle/last-run.gradle.kts")
apply(from = "gradle/test-world.gradle.kts")
// NilLoader is optional (gradle.properties: loaders).
if ("nil" in property("loaders").toString().split(",").map { it.trim() }) {
    apply(from = "gradle/nil-check.gradle.kts")
}
