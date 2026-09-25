package net.momirealms.sparrow.sync.gradle

import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import xyz.jpenilla.runpaper.task.RunServer

fun RunServer.configureBackendServer(display: String, minecraftVersion: String, directory: String, maximumHeap: String) {
    group = "run paper"
    displayName.set(display)
    minecraftVersion(minecraftVersion)
    runDirectory.set(project.rootProject.layout.projectDirectory.dir(directory))
    pluginJars.from(project.tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
        vendor.set(JvmVendorSpec.JETBRAINS)
        languageVersion.set(JavaLanguageVersion.of(if (minecraftVersion.startsWith("26.")) 25 else 21))
    })
    minHeapSize = maximumHeap
    maxHeapSize = maximumHeap
    legacyPluginLoading()

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
