package net.momirealms.sparrow.sync.locale;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import net.momirealms.sparrow.sync.compatibility.CompatibilityManager;
import net.momirealms.sparrow.sync.plugin.configuration.ConfigurationManager;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.plugin.classpath.ClassPathAppender;
import net.momirealms.sparrow.sync.plugin.dependency.Dependency;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyManager;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.plugin.logger.PluginLogger;
import net.momirealms.sparrow.sync.plugin.scheduler.SchedulerAdapter;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.route.Route;
import net.momirealms.sparrow.yaml.YamlDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationManagerImplTest {
    private static final String TRANSLATION_KEY = "test.translation";
    private static final String OLD_TRANSLATION = "old-value";
    private static final String NEW_TRANSLATION = "new-value-longer";

    @TempDir
    Path directory;

    private Field pluginConfigField;
    private Object previousPluginConfig;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        TranslationManagerImpl.instance = null;
        this.pluginConfigField = PluginConfig.class.getDeclaredField("config");
        this.pluginConfigField.setAccessible(true);
        this.previousPluginConfig = this.pluginConfigField.get(null);
        PluginConfig.ConfigDefinition config = new PluginConfig.ConfigDefinition();
        Field forcedLocale = PluginConfig.ConfigDefinition.class.getDeclaredField("forcedLocale");
        forcedLocale.setAccessible(true);
        forcedLocale.set(config, "en");
        this.pluginConfigField.set(null, config);
    }

    @AfterEach
    void tearDown() throws IllegalAccessException {
        TranslationManagerImpl.instance = null;
        this.pluginConfigField.set(null, this.previousPluginConfig);
    }

    @Test
    void keepsThePublishedTranslationsWhileReloadBuilds() throws Exception {
        TestPlugin plugin = new TestPlugin(this.directory, translationYaml(OLD_TRANSLATION));
        TranslationManagerImpl manager = new TranslationManagerImpl(plugin);
        manager.reload();
        assertEquals(OLD_TRANSLATION, manager.miniMessageTranslation(TRANSLATION_KEY));

        Files.writeString(this.directory.resolve("translations/en.yml"), translationYaml(NEW_TRANSLATION), StandardCharsets.UTF_8);
        SaveBlock saveBlock = plugin.blockNextSave();
        CountDownLatch reloadDone = new CountDownLatch(1);
        AtomicReference<Throwable> reloadFailure = new AtomicReference<>();
        Thread.ofPlatform().start(() -> {
            try {
                manager.reload();
            } catch (Throwable throwable) {
                reloadFailure.set(throwable);
            } finally {
                reloadDone.countDown();
            }
        });

        try {
            assertTrue(saveBlock.entered().await(5, TimeUnit.SECONDS));
            assertEquals(OLD_TRANSLATION, manager.miniMessageTranslation(TRANSLATION_KEY));
        } finally {
            saveBlock.release().countDown();
            assertTrue(reloadDone.await(5, TimeUnit.SECONDS));
        }

        assertNull(reloadFailure.get());
        assertEquals(NEW_TRANSLATION, manager.miniMessageTranslation(TRANSLATION_KEY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void joinsSequenceTranslationElements() throws Exception {
        SparrowYaml yaml = SparrowYaml.builder().build();
        YamlDocument document = yaml.load("""
                message:
                  - first
                  - second
                """);
        Method loadLangData = TranslationManagerImpl.class.getDeclaredMethod("loadLangData", YamlDocument.class);
        loadLangData.setAccessible(true);
        Map<String, String> translations = (Map<String, String>) loadLangData.invoke(null, document);

        assertEquals("first<reset><newline>second", translations.get("message"));
    }

    @Test
    void upgradingShutdownTranslationsKeepsCustomTextAndAddsNewKeys() throws Exception {
        String bundled;
        try (InputStream input = this.getClass().getResourceAsStream("/translations/en.yml")) {
            bundled = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path folder = this.directory.resolve("translations");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("en.yml"), """
                lang-version: "39"
                log.sync.shutdown_saved: 'custom shutdown text'
                """);
        TranslationManagerImpl manager = new TranslationManagerImpl(new TestPlugin(this.directory, bundled));
        manager.reload();
        assertEquals("custom shutdown text", manager.miniMessageTranslation(LogConstants.SYNC_SHUTDOWN_SAVED));
        assertTrue(manager.miniMessageTranslation(LogConstants.SYNC_SHUTDOWN_STALLED).contains("<arg:0>/<arg:1>"));
        String written = Files.readString(folder.resolve("en.yml"));
        assertEquals(DependencyVersions.LANG_VERSION, SparrowYaml.builder().build().load(written).getString(Route.from("lang-version")));
        assertTrue(written.contains(LogConstants.SYNC_SHUTDOWN_SUMMARY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shutdownTranslationArgumentsMatchInBothLanguages() throws Exception {
        Map<String, Integer> keys = Map.of(
                LogConstants.SYNC_SHUTDOWN_PROGRESS, 2,
                LogConstants.SYNC_SHUTDOWN_STALLED, 4,
                LogConstants.SYNC_SHUTDOWN_TIMEOUT, 2,
                LogConstants.SYNC_SHUTDOWN_INTERRUPTED, 0,
                LogConstants.SYNC_SHUTDOWN_SUMMARY, 4,
                LogConstants.SYNC_SHUTDOWN_MAPS, 0,
                LogConstants.SYNC_SHUTDOWN_EXECUTOR, 0);
        Method loadLangData = TranslationManagerImpl.class.getDeclaredMethod("loadLangData", YamlDocument.class);
        loadLangData.setAccessible(true);
        for (String language : List.of("en", "zh_cn")) {
            try (InputStream input = this.getClass().getResourceAsStream("/translations/" + language + ".yml")) {
                Map<String, String> translations = (Map<String, String>) loadLangData.invoke(null, SparrowYaml.builder().build().load(input));
                for (Map.Entry<String, Integer> entry : keys.entrySet()) {
                    String text = translations.get(entry.getKey());
                    for (int i = 0; i < entry.getValue(); i++) {
                        assertTrue(text.contains("<arg:" + i + ">"), entry.getKey());
                    }
                    assertEquals(entry.getValue().intValue(), text.split("<arg:", -1).length - 1, entry.getKey());
                }
            }
        }
    }

    private static String translationYaml(String value) {
        return """
                lang-version: "%s"
                %s: '%s'
                """.formatted(DependencyVersions.LANG_VERSION, TRANSLATION_KEY, value);
    }

    private record SaveBlock(CountDownLatch entered, CountDownLatch release) {
    }

    private static final class TestPlugin implements Plugin {
        private static final PluginLogger LOGGER = new QuietLogger();

        private final Path directory;
        private final String embeddedTranslation;
        private final ConfigurationManager configurationManager;
        private volatile SaveBlock saveBlock;

        private TestPlugin(Path directory, String embeddedTranslation) {
            this.directory = directory;
            this.embeddedTranslation = embeddedTranslation;
            this.configurationManager = new ConfigurationManager(this);
        }

        private SaveBlock blockNextSave() {
            SaveBlock block = new SaveBlock(new CountDownLatch(1), new CountDownLatch(1));
            this.saveBlock = block;
            return block;
        }

        @Override
        public boolean isReloading() {
            return false;
        }

        @Override
        public boolean isInitializing() {
            return false;
        }

        @Override
        public String pluginVersion() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String serverVersion() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PluginLogger logger() {
            return LOGGER;
        }

        @Override
        public File dataFolderFile() {
            return this.directory.toFile();
        }

        @Override
        public Path dataFolderPath() {
            return this.directory;
        }

        @Override
        public void onPluginBootstrap(BootstrapContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onPluginLoad() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onPluginEnable() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onPluginReload() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onPluginDisable() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Dependency> platformDependencies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setupProxy() {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream resourceStream(String filePath) {
            if ("translations/_index.json".equals(filePath)) {
                return new ByteArrayInputStream("{\"file\":[\"en.yml\"]}".getBytes(StandardCharsets.UTF_8));
            }
            if ("translations/en.yml".equals(filePath)) {
                return new ByteArrayInputStream(this.embeddedTranslation.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }

        @Override
        public void saveResource(String filePath) {
            SaveBlock block = this.saveBlock;
            if (block != null) {
                block.entered().countDown();
                try {
                    block.release().await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(exception);
                } finally {
                    this.saveBlock = null;
                }
            }

            Path target = this.directory.resolve(filePath);
            if (Files.exists(target)) return;
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, this.embeddedTranslation, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public ClassPathAppender sharedClassPathAppender() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassPathAppender privateClassPathAppender() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <W> SchedulerAdapter<W> scheduler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public DependencyManager dependencyManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompatibilityManager compatibilityManager() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigurationManager configurationManager() {
            return this.configurationManager;
        }

        @Override
        public TranslationManager translationManager() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class QuietLogger implements PluginLogger {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void warn(String message, Throwable throwable) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}
