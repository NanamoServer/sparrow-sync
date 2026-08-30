import org.gradle.kotlin.dsl.buildConfigField
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService
import java.text.SimpleDateFormat
import java.util.Date

// Plugin
plugins {
    alias(libs.plugins.paper.weight)
    alias(libs.plugins.plugin.yml)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.buildconfig)
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
    compileOnly(libs.datafixerupper)
    compileOnly(libs.lettuce.core)
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.zstd.jni)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(libs.lettuce.core)
    testImplementation(libs.datafixerupper)
    testImplementation(libs.zstd.jni)
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

// Run Task
val exampleJar = tasks.shadowJar.flatMap { it.archiveFile }
val minecraftVersions = listOf("1.21.4", "1.21.8", "1.21.11", "26.1.2", "26.2")
for (minecraftVersion in minecraftVersions) {
    tasks.register<RunServer>("runPaper_$minecraftVersion") {
        group = "run paper"
        displayName.set("Paper $minecraftVersion")
        minecraftVersion(minecraftVersion)
        runDirectory.set(rootProject.layout.projectDirectory.dir("run/paper/$minecraftVersion"))
        pluginJars.from(exampleJar)
        javaLauncher = javaToolchains.launcherFor {
            vendor = JvmVendorSpec.JETBRAINS
            languageVersion = JavaLanguageVersion.of(25)
        }
        systemProperties["Paper.IgnoreJavaVersion"] = true
        systemProperties["net.nyana.plugin.dev"] = true
        systemProperties["com.mojang.eula.agree"] = true
        jvmArgs(
            "-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://maven.aliyun.com/repository/central",
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-Ddisable.watchdog=true",
            "-Xlog:redefine+class*=info",
            "-XX:+AllowEnhancedClassRedefinition"
        )
    }

    tasks.register<RunServer>("runFolia_$minecraftVersion") {
        group = "run paper"
        displayName.set("Folia $minecraftVersion")
        downloadsApiService.set(DownloadsAPIService.folia(project))
        minecraftVersion(minecraftVersion)
        runDirectory.set(rootProject.layout.projectDirectory.dir("run/folia/$minecraftVersion"))
        pluginJars.from(exampleJar)
        javaLauncher = javaToolchains.launcherFor {
            vendor = JvmVendorSpec.JETBRAINS
            languageVersion = JavaLanguageVersion.of(25)
        }
        systemProperties["Paper.IgnoreJavaVersion"] = true
        systemProperties["net.nyana.plugin.dev"] = true
        systemProperties["com.mojang.eula.agree"] = true
        jvmArgs(
            "-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://maven.aliyun.com/repository/central",
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-Ddisable.watchdog=true",
            "-Xlog:redefine+class*=info",
            "-XX:+AllowEnhancedClassRedefinition"
        )
    }
}