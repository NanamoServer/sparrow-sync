import net.momirealms.sparrow.sync.gradle.InitializeRunDirectory
import net.momirealms.sparrow.sync.gradle.configureBackendServer
import xyz.jpenilla.runpaper.task.RunServer
import xyz.jpenilla.runtask.service.DownloadsAPIService

plugins {
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
}

val runTemplatesDirectory = rootProject.layout.projectDirectory.dir("buildSrc/run-templates")
val paperConfigurationVersions = mapOf(
    "1.21.4" to "29",
    "1.21.8" to "30",
    "1.21.10" to "31",
    "1.21.11" to "31",
    "26.1.2" to "31",
    "26.2" to "31",
    "26.3" to "31"
)

tasks.withType<RunServer>().configureEach {
    if (name == "runServer" || name == "runDevBundleServer") {
        group = null
        enabled = false
    }
}

val minecraftVersions = paperConfigurationVersions.keys
val extraPluginJars = rootProject.fileTree("buildSrc/plugin") {
    include("*.jar")
}

for (minecraftVersion in minecraftVersions) {
    // 代理后端使用独立目录.
    val paperProxyDirectory = rootProject.layout.projectDirectory.dir("run/proxy/paper/$minecraftVersion")

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

    tasks.register<RunServer>("runProxyPaper_$minecraftVersion") {
        configureBackendServer(
            "Proxy Paper $minecraftVersion",
            minecraftVersion,
            "run/proxy/paper/$minecraftVersion",
            "1536M"
        )
        description = "Run the Paper $minecraftVersion proxy backend on port 25566."
        pluginJars.from(extraPluginJars)
        dependsOn(prepareProxyPaper)
    }

    if (minecraftVersion != "26.3") {
        val foliaProxyDirectory = rootProject.layout.projectDirectory.dir("run/proxy/folia/$minecraftVersion")
        val prepareProxyFolia = tasks.register<InitializeRunDirectory>("prepareProxyFolia_$minecraftVersion") {
            templateDirectories.from(
                commonBackendTemplates,
                versionBackendTemplates,
                runTemplatesDirectory.dir("backend/folia")
            )
            targetDirectory.set(foliaProxyDirectory)
        }

        tasks.register<RunServer>("runProxyFolia_$minecraftVersion") {
            configureBackendServer(
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
}
