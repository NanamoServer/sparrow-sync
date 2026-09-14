import net.momirealms.sparrow.sync.gradle.InitializeRunDirectory
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService
import xyz.jpenilla.runvelocity.task.RunVelocity

plugins {
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
}

/**
 * 配置模板和运行时环境.
 */
val velocityDirectory = rootProject.layout.projectDirectory.dir("run/proxy/velocity")
val runTemplatesDirectory = rootProject.layout.projectDirectory.dir("buildSrc/run-templates")
val javaToolchains = extensions.getByType<JavaToolchainService>()
val java25 = javaToolchains.launcherFor {
    vendor = JvmVendorSpec.JETBRAINS
    languageVersion = JavaLanguageVersion.of(25)
}
// 给运行的不同版本的 Paper/Folia 映射 paper-global.yml 的配置版本.
val paperConfigurationVersions = mapOf(
    "1.21.4" to "29",
    "1.21.8" to "30",
    "1.21.10" to "31",
    "1.21.11" to "31",
    "26.1.2" to "31",
    "26.2" to "31"
)



/**
 * 配置和注册 Velocity 运行测试.
 */
val prepareProxyVelocity = tasks.register<InitializeRunDirectory>("prepareProxyVelocity") {
    templateDirectories.from(runTemplatesDirectory.dir("velocity"))
    targetDirectory.set(velocityDirectory)
}
tasks.register<RunVelocity>("runProxyVelocity") {
    group = "run paper"
    description = "Run the shared Velocity proxy on port 25565."
    displayName.set("Velocity 4.1.1")

    velocityVersion("4.1.1")
    runDirectory.set(velocityDirectory)
    pluginJars.from(rootProject.fileTree("buildSrc/velocity-plugin") {
        include("*.jar")
    })
    javaLauncher.set(java25)
    minHeapSize = "512M"
    maxHeapSize = "512M"

    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8"
    )
    dependsOn(prepareProxyVelocity)
}



/**
 * 配置和注册后端服务器测试.
 */
tasks.withType<RunServer>().configureEach {
    if (name == "runServer" || name == "runDevBundleServer") {
        group = null
        enabled = false
    }
}

val minecraftVersions = listOf("1.21.4", "1.21.8", "1.21.10", "1.21.11", "26.1.2", "26.2")
val projectJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
val extraPluginJars = rootProject.fileTree("buildSrc/plugin") {
    include("*.jar")
}
val bukkitPluginJars = rootProject.fileTree("buildSrc/bukkit-plugin") {
    include("*.jar")
}
// 启动前复制到各自的运行目录, 避免服务端直接持有共享的 shadowJar.
tasks.withType<RunServer>().configureEach {
    legacyPluginLoading()
}
fun RunServer.configureServer(
    display: String,
    minecraftVersion: String,
    directory: String,
    maximumHeap: String? = null
) {
    group = "run paper"
    displayName.set(display)
    minecraftVersion(minecraftVersion)
    runDirectory.set(rootProject.layout.projectDirectory.dir(directory))
    pluginJars.from(projectJar)
    javaLauncher.set(java25)

    if (maximumHeap != null) {
        minHeapSize = maximumHeap
        maxHeapSize = maximumHeap
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

for (minecraftVersion in minecraftVersions) {
    // 代理后端使用独立目录.
    val paperProxyDirectory = rootProject.layout.projectDirectory.dir("run/proxy/paper/$minecraftVersion")
    val foliaProxyDirectory = rootProject.layout.projectDirectory.dir("run/proxy/folia/$minecraftVersion")

    val paperConfigurationVersion = paperConfigurationVersions.getValue(minecraftVersion)
    val commonBackendTemplates = runTemplatesDirectory.dir("backend/common")
    val versionBackendTemplates = runTemplatesDirectory.dir("backend/versions/$paperConfigurationVersion")

    val prepareProxyPaper = tasks.register<InitializeRunDirectory>("prepareProxyPaper_$minecraftVersion") {
        templateDirectories.from(
            commonBackendTemplates,
            versionBackendTemplates,
            runTemplatesDirectory.dir("backend/paper")
        )
        targetDirectory.set(paperProxyDirectory)
    }

    val prepareProxyFolia = tasks.register<InitializeRunDirectory>("prepareProxyFolia_$minecraftVersion") {
        templateDirectories.from(
            commonBackendTemplates,
            versionBackendTemplates,
            runTemplatesDirectory.dir("backend/folia")
        )
        targetDirectory.set(foliaProxyDirectory)
    }

    tasks.register<RunServer>("runProxyPaper_$minecraftVersion") {
        configureServer(
            "Proxy Paper $minecraftVersion",
            minecraftVersion,
            "run/proxy/paper/$minecraftVersion",
            "1536M"
        )
        description = "Run the Paper $minecraftVersion proxy backend on port 25566."
        pluginJars.from(extraPluginJars)
        dependsOn(prepareProxyPaper)
    }

    tasks.register<RunServer>("runProxyFolia_$minecraftVersion") {
        configureServer(
            "Proxy Folia $minecraftVersion",
            minecraftVersion,
            "run/proxy/folia/$minecraftVersion",
            "1536M"
        )
        description = "Run the Folia $minecraftVersion proxy backend on port 25567."
        pluginJars.from(extraPluginJars)
        downloadsApiService.set(DownloadsAPIService.folia(project))
        dependsOn(prepareProxyFolia)
    }
}

// Spigot
val spigotJar = rootProject.layout.projectDirectory.file("buildSrc/server-jars/spigot-26.2.jar")
if (spigotJar.asFile.isFile) {
    val spigotDirectory = rootProject.layout.projectDirectory.dir("run/spigot/26.2")
    val prepareSpigot = tasks.register<InitializeRunDirectory>("prepareSpigot_26.2") {
        templateDirectories.from(runTemplatesDirectory.dir("backend/spigot"))
        targetDirectory.set(spigotDirectory)
    }
    tasks.register<RunServer>("runSpigot_26.2") {
        configureServer("Spigot 26.2", "26.2", "run/spigot/26.2", "1536M")
        description = "Run the standalone Spigot 26.2 server on port 25568."
        pluginJars.from(bukkitPluginJars)
        serverJar(spigotJar.asFile)
        dependsOn(prepareSpigot)
    }
}
