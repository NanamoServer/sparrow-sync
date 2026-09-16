package net.momirealms.sparrow.sync.locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.locale.tag.IndexedArgumentTag;
import net.momirealms.sparrow.sync.util.AdventureHelper;
import net.momirealms.sparrow.sync.util.FileUtils;
import net.momirealms.sparrow.sync.util.GsonHelper;
import net.momirealms.sparrow.sync.util.MiscUtils;
import net.momirealms.sparrow.sync.util.PlayerUtils;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.momirealms.sparrow.yaml.route.Route;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class TranslationManagerImpl implements TranslationManager {
    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
    static TranslationManager instance;
    private final Plugin plugin;
    private final Path translationsDirectory;
    private final String langVersion;
    private final Set<String> supportedLanguages;
    private final Map<String, String> translationFallback = new LinkedHashMap<>();
    // LangId -> (TranslationKey -> Value)
    private volatile Map<String, ClientLangData> clientLangData = new ConcurrentHashMap<>();
    // TranslationKey -> (Lang -> Value)
    private volatile ServerTranslations serverTranslations = new ServerTranslations(DEFAULT_LOCALE, Map.of());
    private Map<Locale, CachedTranslation> cachedTranslations = Map.of();

    public TranslationManagerImpl(Plugin plugin) {
        if (instance != null) {
            throw new IllegalStateException();
        }
        instance = this;
        this.plugin = plugin;
        this.translationsDirectory = this.plugin.dataFolderPath().resolve("translations");
        this.langVersion = DependencyVersions.LANG_VERSION;
        this.supportedLanguages = getSupportedLanguages();
        try {
            YamlDocument langDocument = this.plugin.configurationManager().sparrowYaml().loadFromResource("translations/en.yml");
            Map<String, String> data = loadLangData(langDocument);
            if (!data.isEmpty()) {
                this.translationFallback.putAll(data);
            }
        } catch (IOException e) {
            plugin.logger().warn("Failed to load default translation file", e);
        } catch (Exception e) {
            plugin.logger().error("YAML syntax error in default translation file", e);
        }
    }


    /**
     * 延迟处理客户端翻译数据.
     * 当前实现会在全部客户端语言数据注册完成后, 对每个语言包执行一次键处理器展开.
     */
    public synchronized void delayedLoad() {
        this.clientLangData.values().forEach(ClientLangData::processTranslations);
    }

    /**
     * 重新加载全部翻译数据.
     */
    @Override
    public synchronized void reload() {
        for (String lang : this.supportedLanguages) {
            this.plugin.saveResource("translations/" + lang + ".yml");
        }

        Map<Locale, CachedTranslation> cachedTranslations = this.readFromFileSystem(this.translationsDirectory);
        Map<String, ServerLangData> serverLangData = new HashMap<>();
        Set<Locale> installed = new HashSet<>();
        this.loadFromCache(cachedTranslations, serverLangData, installed);
        LocaleSelection selection = this.selectLocale(installed);

        this.cachedTranslations = cachedTranslations;
        this.clientLangData = new ConcurrentHashMap<>();
        // 默认语言与翻译表同代发布, 每次查询只会读到一份完整状态
        this.serverTranslations = new ServerTranslations(selection.selectedLocale(), Map.copyOf(serverLangData));
        if (selection.missingLocale() != null) {
            Locale missingLocale = selection.missingLocale();
            this.plugin.logger().warn(this.plainTranslation(LogConstants.LOCALE_MISSING_FILE, DEFAULT_LOCALE, missingLocale.toString().toLowerCase(Locale.ENGLISH), DEFAULT_LOCALE.toString().toLowerCase(Locale.ENGLISH)));
        }
    }

    /**
     * 查询指定翻译键对应的翻译文本.
     * 若调用方未指定语言环境, 则使用当前选定语言.
     * 若键不存在或该语言环境下无可用翻译, 则返回原始键作为回退结果.
     *
     * @param key 翻译键
     * @param locale 目标语言环境, 为 null 时使用当前选定语言
     * @return 对应的 MiniMessage 翻译文本, 或原始键
     */
    @Override
    @NotNull
    public String miniMessageTranslation(@NotNull String key, @Nullable Locale locale) {
        ServerTranslations snapshot = this.serverTranslations;
        ServerLangData serverLangData = snapshot.translations().get(key);
        if (serverLangData == null) {
            return key;
        }
        if (locale == null) {
            locale = snapshot.selectedLocale();
        }
        return Optional.ofNullable(serverLangData.translate(locale)).orElse(key);
    }

    /**
     * 渲染一个可翻译 Adventure 组件.
     * 该方法会先查询翻译文本, 再根据组件参数决定是否使用 `IndexedArgumentTag` 进行参数填充, 最后保留原组件的 children 结构.
     * 若翻译为空字符串则返回空组件, 若翻译缺失则以原始键渲染.
     *
     * @param component 需要渲染的可翻译组件
     * @param locale 目标语言环境, 为 null 时使用当前选定语言
     * @return 渲染后的 Adventure 组件
     * @throws RuntimeException 当 MiniMessage 解析失败或参数展开失败时, 底层实现可能抛出运行时异常
     */
    @Override
    @NotNull
    public Component render(@NotNull TranslatableComponent component, @Nullable Locale locale) {
        String miniMessageTranslation = miniMessageTranslation(component.key(), locale);
        if (miniMessageTranslation.isEmpty()) {
            return Component.empty();
        }
        final Component resultingComponent = component.arguments().isEmpty()
                ? AdventureHelper.miniMessage().deserialize(miniMessageTranslation)
                : AdventureHelper.miniMessage().deserialize(miniMessageTranslation, new IndexedArgumentTag(component.arguments()));
        if (component.children().isEmpty()) {
            return resultingComponent;
        } else {
            return resultingComponent.children(component.children());
        }
    }

    @Override
    public void log(@NotNull String id, @NotNull String... args) {
        String translation = miniMessageTranslation(id);
        if (translation.isEmpty()) {
            translation = id;
        }
        PlayerUtils.sendMessage(Bukkit.getServer().getConsoleSender(), AdventureHelper.miniMessage().deserialize(translation, new IndexedArgumentTag(Arrays.stream(args).map(Component::text).toList())));
    }

    @Override
    public Set<String> translationKeys() {
        return this.serverTranslations.translations().keySet();
    }

    @Override
    public Map<String, ClientLangData> clientLangData() {
        return Collections.unmodifiableMap(this.clientLangData);
    }

    @Override
    public synchronized void addClientTranslation(String langId, Map<String, String> translations) {
        Map<String, ClientLangData> clientLangData = this.clientLangData;
        // `all`, 向全部已知客户端语言广播追加.
        if ("all".equals(langId)) {
            ALL_LANG.forEach(lang -> clientLangData.computeIfAbsent(lang, k -> new ClientLangData())
                    .addTranslations(translations));
            return;
        }
        // 完整语言标识, 仅向该语言追加.
        if (ALL_LANG.contains(langId)) {
            clientLangData.computeIfAbsent(langId, k -> new ClientLangData())
                    .addTranslations(translations);
            return;
        }
        // 仅语言前缀, 向该语言对应的所有国家或地区变体追加.
        List<String> langCountries = LOCALE_2_COUNTRIES.getOrDefault(langId, Collections.emptyList());
        for (String lang : langCountries) {
            clientLangData.computeIfAbsent(langId + "_" + lang, k -> new ClientLangData())
                    .addTranslations(translations);
        }
    }

    /**
     * 从文件缓存组装一份待发布的服务端翻译表.
     * 为提高兼容性, 该方法分两个阶段执行:
     * 第一步先注册仅包含语言代码的语言环境, 避免后续完整语言环境覆盖其回退语义.
     * 第二步再注册包含国家或地区信息的完整语言环境, 并在需要时补充注册仅语言代码版本.
     *
     * @param cachedTranslations 已加载的语言文件缓存
     * @param serverLangData 正在组装的服务端翻译表
     * @param installed 已载入的语言环境
     */
    private void loadFromCache(
            Map<Locale, CachedTranslation> cachedTranslations,
            Map<String, ServerLangData> serverLangData,
            Set<Locale> installed
    ) {
        // 第一阶段, 先注册所有没有国家或地区的 locale.
        for (Map.Entry<Locale, CachedTranslation> entry : cachedTranslations.entrySet()) {
            Locale locale = entry.getKey();
            // 只处理没有国家或地区的 locale.
            if (locale.getCountry().isEmpty()) {
                this.registerAll(locale, entry.getValue().translations, serverLangData, installed);
            }
        }

        // 第二阶段, 再注册其他完整的 locale, 即包含国家或地区信息的 locale.
        for (Map.Entry<Locale, CachedTranslation> entry : cachedTranslations.entrySet()) {
            Locale locale = entry.getKey();
            // 跳过已在第一阶段处理过的无国家 locale.
            if (!locale.getCountry().isEmpty()) {
                this.registerAll(locale, entry.getValue().translations, serverLangData, installed);

                // 如有需要, 也为完整 locale 补一个仅语言代码的兼容版本.
                Locale localeWithoutCountry = Locale.of(locale.getLanguage());
                if (!installed.contains(localeWithoutCountry) && !localeWithoutCountry.equals(DEFAULT_LOCALE)) {
                    this.registerAll(localeWithoutCountry, entry.getValue().translations, serverLangData, installed);
                }
            }
        }
    }

    /**
     * 将某个语言环境下的全部翻译键值对注册到待发布的服务端翻译表中.
     * 若某个翻译键尚未出现, 会先创建 `ServerLangData` 并注入回退文本, 再将当前语言环境对应的翻译加入其中.
     *
     * @param locale 需要注册的语言环境
     * @param cachedTranslation 该语言环境下缓存的全部翻译键值对
     * @param serverLangData 正在构建的服务端翻译表
     * @param installed 已载入的语言环境
     */
    private void registerAll(
            Locale locale,
            Map<String, String> cachedTranslation,
            Map<String, ServerLangData> serverLangData,
            Set<Locale> installed
    ) {
        for (Map.Entry<String, String> translation : cachedTranslation.entrySet()) {
            serverLangData.computeIfAbsent(translation.getKey(), k -> new ServerLangData(this.translationFallback.get(translation.getKey())))
                    .addTranslation(locale, translation.getValue());
        }
        installed.add(locale);
    }

    /**
     * 从插件内置的 `translations/_index.json` 中读取支持的语言列表.
     * 算法步骤为打开资源流, 解析 JSON, 读取 `file` 数组中的文件名, 去除扩展名后组装为语言集合.
     *
     * @return 插件内置支持的语言标识集合, 当资源不存在或读取失败时返回空集合
     */
    private Set<String> getSupportedLanguages() {
        InputStream stream = this.plugin.resourceStream("translations/_index.json");
        if (stream == null) return Set.of();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject json = GsonHelper.get().fromJson(reader, JsonObject.class);
            Set<String> supportedLanguages = new HashSet<>();
            for (JsonElement file : json.getAsJsonArray("file")) {
                supportedLanguages.add(FileUtils.pathWithoutExtension(file.getAsString()));
            }
            return supportedLanguages;
        } catch (IOException e) {
            this.plugin.logger().warn("Failed to load default translation file", e);
            return Set.of();
        }
    }

    /**
     * 从指定目录递归扫描并加载语言文件.
     * 该方法会遍历所有 `.yml` 文件, 解析文件名得到 `Locale`, 并结合文件修改时间与大小决定是否复用旧缓存.
     * 当检测到语言文件版本与当前程序要求不一致时, 会尝试自动更新文件内容并重新写回磁盘.
     *
     * @param directory 需要扫描的翻译目录
     */
    public synchronized void loadFromFileSystem(Path directory) {
        this.cachedTranslations = this.readFromFileSystem(directory);
    }

    private Map<Locale, CachedTranslation> readFromFileSystem(Path directory) {
        Map<Locale, CachedTranslation> previousTranslations = this.cachedTranslations;
        Map<Locale, CachedTranslation> loadedTranslations = new HashMap<>();
        try {
            Files.walkFileTree(directory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                @NotNull
                public FileVisitResult visitFile(@NotNull Path path, @NotNull BasicFileAttributes attrs) {
                    String fileName = path.getFileName().toString();
                    if (Files.isRegularFile(path) && fileName.endsWith(".yml")) {
                        // 检查文件名是否规范
                        String localeName = fileName.substring(0, fileName.length() - ".yml".length());
                        Locale locale = TranslationManager.parseLocale(localeName);
                        if (locale == null) {
                            TranslationManagerImpl.this.plugin.logger().warn("Invalid translation file " + path);
                            return FileVisitResult.CONTINUE;
                        }
                        // 比对上次缓存文件和本次即将读取文件, 如果一致则跳过.
                        CachedTranslation cachedFile = previousTranslations.get(locale);
                        long lastModifiedTime = attrs.lastModifiedTime().toMillis();
                        long size = attrs.size();
                        if (cachedFile != null && cachedFile.lastModified() == lastModifiedTime && cachedFile.size() == size) {
                            loadedTranslations.put(locale, cachedFile);
                        }
                        // 读取文件
                        else {
                            try (InputStream inputStream = Files.newInputStream(path)) {
                                // 读取
                                YamlDocument locLangDocument = plugin.configurationManager().sparrowYaml().load(inputStream);
                                Map<String, String> langData = loadLangData(locLangDocument);
                                if (langData.isEmpty()) return FileVisitResult.CONTINUE;
                                // 更新
                                String langVersion = locLangDocument.getOrDefault("", String.class, Route.from("lang-version"));
                                if (!TranslationManagerImpl.this.langVersion.equals(langVersion) && TranslationManagerImpl.this.supportedLanguages.contains(localeName)) {
                                    langData = updateLangFile(langData, path);
                                    BasicFileAttributes updatedAttrs = Files.readAttributes(path, BasicFileAttributes.class);
                                    lastModifiedTime = updatedAttrs.lastModifiedTime().toMillis();
                                    size = updatedAttrs.size();
                                }
                                // 缓存
                                cachedFile = new CachedTranslation(langData, lastModifiedTime, size);
                                loadedTranslations.put(locale, cachedFile);
                            } catch (IOException e) {
                                TranslationManagerImpl.this.plugin.logger().error("Error while reading translation file: " + path, e);
                                return FileVisitResult.CONTINUE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            this.plugin.logger().warn("Failed to load translation file from folder", e);
        }
        return loadedTranslations;
    }

    /**
     * 更新旧版本语言文件并将结果重新写回磁盘.
     * 算法步骤为读取插件内置同名语言模板, 写入新的 `lang-version`, 合并默认回退翻译, 合并内置新版语言内容, 最后再覆盖保留旧文件中的用户自定义值.
     * 这样既可以引入新版新增键, 又尽可能保留用户已有修改.
     *
     * @param previous 旧语言文件解析得到的键值对
     * @param translationFile 需要更新的语言文件路径
     * @return 更新并最终写回磁盘的完整翻译映射
     * @throws IOException 当读取模板, 处理 YAML 或写回文件失败时抛出
     */
    private Map<String, String> updateLangFile(Map<String, String> previous, Path translationFile) throws IOException {
        String fileName = translationFile.getFileName().toString();
        LinkedHashMap<String, String> newFileContents = new LinkedHashMap<>();

        try (InputStream is = this.plugin.resourceStream("translations/" + fileName)) {
            if (is == null) {
                throw new IOException("Resource not found: translations/" + fileName);
            }

            YamlDocument newDocument = this.plugin.configurationManager().sparrowYaml().load(is);
            Map<String, String> newMap = loadLangData(newDocument);

            newFileContents.put("lang-version", this.langVersion);
            newFileContents.putAll(this.translationFallback);
            newFileContents.putAll(newMap);

            previous.remove("lang-version");
            for (String key : new ArrayList<>(newFileContents.keySet())) {
                if (previous.containsKey(key)) {
                    newFileContents.put(key, previous.get(key));
                }
            }

            YamlDocument outputDocument = this.plugin.configurationManager().sparrowYaml().load("");
            for (Map.Entry<String, String> entry : newFileContents.entrySet()) {
                outputDocument.setAndGet(Route.from(entry.getKey()), entry.getValue());
            }
            outputDocument.save(translationFile);

            return newFileContents;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Error processing YAML for " + fileName, e);
        }
    }

    /**
     * 选择当前生效的服务端语言环境.
     * 优先级依次为强制指定语言, 本机完整语言环境, 本机仅语言代码环境, 最后回退到默认英语.
     */
    private LocaleSelection selectLocale(Set<Locale> installed) {
        Locale forcedLocale = PluginConfig.forcedLocale();
        if (forcedLocale != null) {
            return new LocaleSelection(forcedLocale, null);
        }

        Locale localLocale = Locale.getDefault();
        if (installed.contains(localLocale)) {
            return new LocaleSelection(localLocale, null);
        }

        Locale langLocale = Locale.of(localLocale.getLanguage());
        if (installed.contains(langLocale)) {
            return new LocaleSelection(langLocale, null);
        }

        return new LocaleSelection(DEFAULT_LOCALE, localLocale);
    }

    /**
     * 递归展开嵌套 Map 结构中的语言键.
     * 该方法用于兼容如下层级结构:
     * `a:`
     * `  b:`
     * `    c: xxx`
     * 最终会将其展平成 `a.b.c -> xxx` 的形式交给收集器处理.
     *
     * @param prefix 当前递归层级下的键前缀
     * @param data 当前层级的键值映射
     * @param collector 用于接收展平后键值对的收集器
     */
    private static void loadLangKeyDeeply(String prefix, Map<String, Object> data, BiConsumer<String, String> collector) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() instanceof Map<?,?> map) {
                loadLangKeyDeeply(assembleLangKey(prefix, entry.getKey()), MiscUtils.castToMap(map, false), collector);
            } else {
                collector.accept(assembleLangKey(prefix, entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
    }

    private static String assembleLangKey(String prefix, String lang) {
        if (prefix.isEmpty()) {
            return lang;
        }
        return prefix + "." + lang;
    }

    /**
     * 读取语言数据.
     */
    private static Map<String, String> loadLangData(YamlDocument langDocument) {
        LinkedHashMap<String, String> data = new LinkedHashMap<>();
        langDocument.value().forEach((key, node) -> {
            String langKey = key.toString();
            if (node.isSequence()) {
                StringJoiner stringJoiner = new StringJoiner("<reset><newline>");
                SequenceNode sequenceNode = (SequenceNode) node;
                sequenceNode.value().forEach(yamlNode -> stringJoiner.add(String.valueOf(yamlNode.value())));
                data.put(langKey, stringJoiner.toString());
            } else if (node.isScalar()) {
                data.put(langKey, node.get(String.class));
            } else {
                data.put(langKey, node.value() == null ? null : node.value().toString());
            }
        });
        return data;
    }

    /**
     * 语言文件缓存记录.
     *
     * @param translations 该语言文件解析后的翻译键值集合
     * @param lastModified 文件最后修改时间戳, 单位为毫秒
     * @param size 文件大小, 单位为字节
     */
    private record CachedTranslation(Map<String, String> translations, long lastModified, long size) {
    }

    private record ServerTranslations(@NotNull Locale selectedLocale, @NotNull Map<String, ServerLangData> translations) {
    }

    private record LocaleSelection(@NotNull Locale selectedLocale, @Nullable Locale missingLocale) {
    }
}
