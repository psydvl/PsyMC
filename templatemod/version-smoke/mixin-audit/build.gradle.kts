import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType

plugins {
    `java-library`
}

// One jar for every loader and release. The loader APIs stay compile-only.
version = "1.0.0"
group = "dev.psydvl.mc.smoke"

repositories {
    mavenCentral()
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    maven {
        name = "NeoForged"
        url = uri("https://maven.neoforged.net/releases")
    }
}

dependencies {
    add("compileOnly", "net.fabricmc:fabric-loader:0.19.5") { isTransitive = false }
    add("compileOnly", "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7") { isTransitive = false }
    add("compileOnly", "net.neoforged.fancymodloader:language-java:1.0.16") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.named<Jar>("jar") {
    archiveFileName.set("mixin-audit.jar")
}
