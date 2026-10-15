plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // The nil leaves remap the game jar and write the NilLoader mappings with ASM.
    implementation("org.ow2.asm:asm-commons:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    // mcInspect prints bytecode with the Textifier.
    implementation("org.ow2.asm:asm-util:9.8")
}
