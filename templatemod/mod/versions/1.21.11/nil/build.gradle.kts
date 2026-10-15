plugins {
    `java-library`
    id("net.ltgt.errorprone")
}

apply(from = layout.settingsDirectory.file("gradle/mod-nil.gradle.kts").asFile.path)
