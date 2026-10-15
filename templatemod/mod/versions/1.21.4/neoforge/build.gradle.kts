plugins {
    `java-library`
    id("net.neoforged.moddev")
    id("net.ltgt.errorprone")
}

apply(from = layout.settingsDirectory.file("gradle/mod-neoforge.gradle.kts").asFile.path)
