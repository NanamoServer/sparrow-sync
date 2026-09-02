plugins {
    id("io.papermc.paperweight.userdev")
}

dependencies {
    paperweight.paperDevBundle("1.21.10-R0.1-SNAPSHOT")

    compileOnly(libs.datafixerupper)
    implementation(libs.sparrow.reflection)
}

tasks {
    shadowJar {
        archiveClassifier = ""
        archiveFileName = "sparrow-sync-proxy.jarinjar"
        relocate("net.momirealms.sparrow.reflection", "net.momirealms.sparrow.sync.libraries.reflection")
    }
}