import net.momirealms.sparrow.sync.gradle.InitializeRunDirectory
import net.momirealms.sparrow.sync.gradle.configureBackendServer
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("xyz.jpenilla.run-paper")
}

tasks.withType<RunServer>().configureEach {
    if (name == "runServer" || name == "runDevBundleServer") {
        group = null
        enabled = false
    }
}

val runTemplatesDirectory = rootProject.layout.projectDirectory.dir("buildSrc/run-templates")
val bukkitPluginJars = rootProject.fileTree("buildSrc/bukkit-plugin") {
    include("*.jar")
}

val spigotJars = rootProject.fileTree("buildSrc/server-jars") {
    include("spigot-*.jar")
}

for (spigotJar in spigotJars.sortedBy { it.name }) {
    val minecraftVersion = spigotJar.name.removePrefix("spigot-").removeSuffix(".jar")
    val spigotDirectory = rootProject.layout.projectDirectory.dir("run/spigot/$minecraftVersion")
    val prepareSpigot = tasks.register<InitializeRunDirectory>("prepareSpigot_$minecraftVersion") {
        templateDirectories.from(runTemplatesDirectory.dir("backend/spigot"))
        targetDirectory.set(spigotDirectory)
    }
    tasks.register<RunServer>("runSpigot_$minecraftVersion") {
        configureBackendServer("Spigot $minecraftVersion", minecraftVersion, "run/spigot/$minecraftVersion", "1536M")
        description = "Run the standalone Spigot $minecraftVersion server on port 25568."
        pluginJars.from(bukkitPluginJars)
        serverJar(spigotJar)
        dependsOn(prepareSpigot)
    }
}
