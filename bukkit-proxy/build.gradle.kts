dependencies {
    compileOnly(libs.paper.api)
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