import org.gradle.kotlin.dsl.buildConfigField
import java.text.SimpleDateFormat
import java.util.Date

// Plugin
plugins {
    alias(libs.plugins.plugin.yml)
    alias(libs.plugins.buildconfig)
    id("sparrow-sync.run-servers")
}

// Dependency
dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api)

    compileOnly(project(":bukkit-proxy"))
    implementation(project(":common-files"))

    compileOnly(libs.mojang.brigadier)
    compileOnly(libs.cloud.core)
    compileOnly(libs.cloud.paper)
    compileOnly(libs.cloud.minecraft.extras)

    compileOnly(libs.bundles.adventure)
    implementation(libs.bundles.sparrow)

    compileOnly(libs.sparrow.reflection)
    compileOnly(libs.caffeine)
    compileOnly(libs.datafixerupper)
    compileOnly(libs.lettuce.core)
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.jdbi.core)
    compileOnly(libs.hikari.cp)
    compileOnly(libs.mysql.connector.j) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    compileOnly(libs.zstd.jni)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(libs.jdbi.core)
    testImplementation(libs.hikari.cp)
    testImplementation(libs.mysql.connector.j) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    testImplementation(libs.lettuce.core)
    testImplementation(libs.caffeine)
    testImplementation(libs.datafixerupper)
    testImplementation(libs.zstd.jni)
    testImplementation(project(":bukkit-proxy"))
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.test.paper.api)
}

// Version
buildConfig {
    packageName = "net.momirealms.sparrow.sync.plugin.dependency"
    className = "DependencyVersions"

    buildConfigField("COMPILE_TIME", SimpleDateFormat("yyyyMMdd_HHmm").format(Date()))
    buildConfigField("CONFIG_VERSION", libs.versions.config.version.get())
    buildConfigField("LANG_VERSION", libs.versions.lang.version.get())
    // ASM
    buildConfigField("ASM", libs.versions.asm.get())
    buildConfigField("ASM_COMMONS", libs.versions.asmcommons.get())
    buildConfigField("JAR_RELOCATOR", libs.versions.jar.relocator.get())
    // COMMON
    buildConfigField("CAFFEINE", libs.versions.caffeine.get())
    buildConfigField("MONGODB_DRIVER", libs.versions.mongodb.driver.get())
    buildConfigField("REACTIVE_STREAMS", libs.versions.reactive.streams.get())
    buildConfigField("ZSTD_JNI", libs.versions.zstd.get())
    // MYSQL
    buildConfigField("JDBI", libs.versions.jdbi.get())
    buildConfigField("HIKARI_CP", libs.versions.hikari.cp.get())
    buildConfigField("MYSQL_DRIVER", libs.versions.mysql.driver.get())
    // LETTUCE
    buildConfigField("LETTUCE", libs.versions.lettuce.get())
    buildConfigField("JACKSON", libs.versions.jackson.core.get())
    buildConfigField("JACKSON_ANNOTATIONS", libs.versions.jackson.annotations.get())
    buildConfigField("JACKSON_DATATYPE", libs.versions.jackson.datatype.get())
    buildConfigField("NETTY", libs.versions.netty.get())
    buildConfigField("REACTOR", libs.versions.reactor.get())
    // CLOUD
    buildConfigField("GEANTYREF", libs.versions.geantyref.get())
    buildConfigField("CLOUD_CORE", libs.versions.cloud.core.get())
    buildConfigField("CLOUD_BRIGADIER", libs.versions.cloud.brigadier.get())
    buildConfigField("CLOUD_SERVICES", libs.versions.cloud.services.get())
    buildConfigField("CLOUD_BUKKIT", libs.versions.cloud.bukkit.get())
    buildConfigField("CLOUD_PAPER", libs.versions.cloud.paper.get())
    buildConfigField("CLOUD_MINECRAFT_EXTRAS", libs.versions.cloud.minecraft.extras.get())
    // ADVENTURE
    buildConfigField("ADVENTURE", libs.versions.adventure.get())
    buildConfigField("OPTION", libs.versions.option.get())
    buildConfigField("EXAMINATION_API", libs.versions.examination.api.get())
}

// Tasks
tasks {
    shadowJar {
        mergeServiceFiles()
        manifest {
            attributes["paperweight-mappings-namespace"] = "mojang"
        }
        from(project(":bukkit-proxy").tasks.shadowJar.flatMap { it.archiveFile })
        archiveFileName = "sparrow-sync-${libs.versions.project.version.get()}.jar"
        destinationDirectory.set(file("$rootDir/target"))
    }

    test {
        useJUnitPlatform()
        providers.gradleProperty("sparrow.test.redis").orNull?.let { systemProperty("sparrow.test.redis", it) }
    }
}

// paper-plugin.yml
paper {
    name = "SparrowSync"
    bootstrapper = "net.momirealms.sparrow.sync.plugin.PaperBootstrap"
    main = "net.momirealms.sparrow.sync.plugin.PaperJavaPlugin"
    apiVersion = "1.21.8"
    foliaSupported = true

}
