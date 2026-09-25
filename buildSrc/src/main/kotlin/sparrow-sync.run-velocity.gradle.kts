import net.momirealms.sparrow.sync.gradle.InitializeRunDirectory
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import xyz.jpenilla.runvelocity.task.RunVelocity

val velocityDirectory = rootProject.layout.projectDirectory.dir("run/proxy/velocity")
val runTemplatesDirectory = rootProject.layout.projectDirectory.dir("buildSrc/run-templates")
val java25 = extensions.getByType<JavaToolchainService>().launcherFor {
    vendor = JvmVendorSpec.JETBRAINS
    languageVersion = JavaLanguageVersion.of(25)
}

val prepareProxyVelocity = tasks.register<InitializeRunDirectory>("prepareProxyVelocity") {
    templateDirectories.from(runTemplatesDirectory.dir("velocity"))
    targetDirectory.set(velocityDirectory)
}
tasks.register<RunVelocity>("runProxyVelocity") {
    group = "run paper"
    description = "Run the shared Velocity proxy on port 25565."
    displayName.set("Velocity 4.2.0")

    velocityVersion("4.2.0")
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
