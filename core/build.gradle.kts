import org.gradle.kotlin.dsl.buildConfigField
import net.minecrell.pluginyml.paper.PaperPluginDescription.RelativeLoadOrder
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

    compileOnly(libs.bundles.bstats)
    compileOnly(libs.bundles.cloud)
    compileOnly(libs.bundles.adventure)
    implementation(libs.bundles.sparrow)

    compileOnly(libs.mojang.brigadier)
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
    compileOnly(libs.postgresql.driver)
    compileOnly(libs.mariadb.driver)
    compileOnly(libs.zstd.jni)
    compileOnly(libs.husksync)
    compileOnly(libs.vaultapi)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(libs.jdbi.core)
    testImplementation(libs.hikari.cp)
    testImplementation(libs.mysql.connector.j) {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    testImplementation(libs.postgresql.driver)
    testImplementation(libs.mariadb.driver)
    testImplementation(libs.lettuce.core)
    testImplementation(libs.caffeine)
    testImplementation(libs.husksync)
    testImplementation(libs.vaultapi)
    testImplementation(libs.datafixerupper)
    testImplementation(libs.zstd.jni)
    testImplementation(project(":bukkit-proxy"))
    testRuntimeOnly(libs.junit.platformLauncher)
    testImplementation(libs.mockbukkit)
    testImplementation(libs.test.paper.api)
    testImplementation(libs.bundles.cloud)
}

// Version
buildConfig {
    packageName = "net.momirealms.sparrow.sync.plugin.dependency"
    className = "DependencyVersions"

    buildConfigField("COMPILE_TIME", SimpleDateFormat("yyyyMMdd_HHmm").format(Date()))
    buildConfigField("CONFIG_VERSION", libs.versions.config.version.get())
    buildConfigField("COMMANDS_CONFIG_VERSION", libs.versions.commands.config.version.get())
    buildConfigField("SERVER_CONFIG_VERSION", libs.versions.server.config.version.get())
    buildConfigField("LANG_VERSION", libs.versions.lang.version.get())
    buildConfigField("MONGODB_INDEX_VERSION", libs.versions.mongodb.index.version.get().toInt())
    buildConfigField("MYSQL_SCHEMA_VERSION", libs.versions.mysql.schema.version.get().toInt())
    buildConfigField("POSTGRESQL_SCHEMA_VERSION", libs.versions.postgresql.schema.version.get().toInt())
    buildConfigField("SNAPSHOT_FORMAT_VERSION", libs.versions.snapshot.format.version.get().toInt())
    // ASM
    buildConfigField("ASM", libs.versions.asm.get())
    buildConfigField("ASM_COMMONS", libs.versions.asmcommons.get())
    buildConfigField("JAR_RELOCATOR", libs.versions.jar.relocator.get())
    // COMMON
    buildConfigField("CAFFEINE", libs.versions.caffeine.get())
    buildConfigField("MONGODB_DRIVER", libs.versions.mongodb.driver.get())
    buildConfigField("REACTIVE_STREAMS", libs.versions.reactive.streams.get())
    buildConfigField("ZSTD_JNI", libs.versions.zstd.get())
    // BSTATS
    buildConfigField("BSTATS", libs.versions.bstats.get())
    // MYSQL
    buildConfigField("JDBI", libs.versions.jdbi.get())
    buildConfigField("HIKARI_CP", libs.versions.hikari.cp.get())
    buildConfigField("MYSQL_DRIVER", libs.versions.mysql.driver.get())
    buildConfigField("MARIADB_DRIVER", libs.versions.mariadb.driver.get())
    // POSTGRESQL
    buildConfigField("POSTGRESQL_DRIVER", libs.versions.postgresql.driver.get())
    buildConfigField("CHECKER_QUAL", libs.versions.checker.qual.get())
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
        maxHeapSize = "2g"
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
    serverDependencies {
        register("InvSync") {
            load = RelativeLoadOrder.BEFORE
            required = false
            joinClasspath = true
        }
        register("HuskSync") {
            load = RelativeLoadOrder.BEFORE
            required = false
            joinClasspath = true
        }
        register("Vault") {
            load = RelativeLoadOrder.BEFORE
            required = false
            joinClasspath = true
        }
    }
}
