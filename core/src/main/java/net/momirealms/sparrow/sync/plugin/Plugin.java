package net.momirealms.sparrow.sync.plugin;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import net.momirealms.sparrow.sync.compatibility.CompatibilityManager;
import net.momirealms.sparrow.sync.configuration.ConfigurationManager;
import net.momirealms.sparrow.sync.plugin.dependency.Dependency;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyManager;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.classpath.ClassPathAppender;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public interface Plugin {

    boolean isReloading();

    boolean isInitializing();

    /**
     * 获取插件版本号.
     *
     * @return `plugin.yml` 或描述文件中声明的插件版本字符串
     */
    String pluginVersion();

    /**
     * 获取当前服务端版本号.
     *
     * @return 当前 Minecraft 服务端版本字符串
     */
    String serverVersion();

    PluginLogger logger();

    /**
     * 获取插件数据目录的 `File` 形式.
     *
     * @return 插件数据目录文件对象
     */
    File dataFolderFile();

    /**
     * 获取插件数据目录的 `Path` 形式.
     *
     * @return 插件数据目录路径对象
     */
    Path dataFolderPath();

    void onPluginBootstrap(BootstrapContext context);

    void onPluginLoad();

    void onPluginEnable();

    void onPluginReload();

    void onPluginDisable();

    /**
     * 提供平台依赖列表.
     *
     * @return 通用依赖集合
     */
    List<Dependency> platformDependencies();

    /**
     * 初始化ASM Proxy 反射插件代理.
     */
    void setupProxy();

    /**
     * 根据路径读取插件Jar内的资源流.
     *
     * @param filePath 资源相对路径, 允许包含反斜杠路径分隔符
     * @return 资源输入流, 若资源不存在则返回 null
     */
    InputStream resourceStream(String filePath);

    /**
     * 将插件内置资源保存到数据目录.
     * 该方法会在目标文件不存在时创建父目录, 然后从插件资源中读取数据并写入磁盘.
     *
     * @param filePath 需要保存的资源相对路径
     * @throws IllegalArgumentException 当资源路径为空字符串时抛出
     * @throws RuntimeException 当资源复制过程中发生 I/O 异常时抛出
     */
    void saveResource(String filePath);

    /**
     * 获取共享类路径追加器.
     * 该追加器通常用于向共享类加载环境中注入依赖.
     *
     * @return 共享类路径追加器
     */
    ClassPathAppender sharedClassPathAppender();

    /**
     * 获取私有类路径追加器.
     * 该追加器通常用于向插件私有类加载环境中注入依赖.
     *
     * @return 私有类路径追加器
     */
    ClassPathAppender privateClassPathAppender();

    <W> SchedulerAdapter<W> scheduler();

    DependencyManager dependencyManager();

    CompatibilityManager compatibilityManager();

    ConfigurationManager configurationManager();

    TranslationManager translationManager();
}
