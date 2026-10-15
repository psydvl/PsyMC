plugins {
    `java-library`
    id("net.fabricmc.fabric-loom-remap")
    id("net.ltgt.errorprone")
}

apply(from = layout.settingsDirectory.file("gradle/mod-fabric.gradle.kts").asFile.path)
