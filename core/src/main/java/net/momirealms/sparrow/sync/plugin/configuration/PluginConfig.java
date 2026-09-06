package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.map.handler.MapType;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.plugin.dependency.DependencyVersions;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.Plugin;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.storage.StorageType;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.mapper.YamlMapper;
import net.momirealms.sparrow.yaml.mapper.YamlMapperFactory;
import net.momirealms.sparrow.yaml.route.Route;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.AfterComment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.BlankLineBefore;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Comment;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.Configuration;
import net.momirealms.sparrow.yaml.serializer.auto.annotation.YamlIgnore;
import net.momirealms.sparrow.yaml.upgrade.YamlUpgradePipeline;
import net.momirealms.sparrow.yaml.upgrade.version.FieldVersionExtractor;
import org.bukkit.GameMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PluginConfig {
    private static final String CONFIG_FILE = "config.yml";
    private static volatile ConfigDefinition config;

    private final Plugin plugin;
    private final Path configFilePath;
    private final YamlMapper<ConfigDefinition> configMapper;

    PluginConfig(Plugin plugin, SparrowYaml sparrowYaml) {
        this.plugin = plugin;
        this.configFilePath = plugin.dataFolderPath().resolve(CONFIG_FILE);
        YamlUpgradePipeline upgradePipeline = YamlUpgradePipeline.builder()
                .versionExtractor(new FieldVersionExtractor("config-version"))
                .addPatch("14"::equals, patch -> patch.patch((defaults, local, context) -> {
                    Route type = Route.from("synchronization", "map", "type");
                    Route enabled = Route.from("synchronization", "map", "enabled");
                    String previous = local.getString(type);
                    if ("NONE".equals(previous) || "HIDE".equals(previous)) {
                        if (local.getNodeOrNull(enabled) == null) {
                            local.setAndGet(enabled, "HIDE".equals(previous));
                        }
                        local.setAndGet(type, "HIDE");
                    }
                    return local;
                }))
                .build();
        YamlMapperFactory mapperFactory = YamlMapperFactory.builder()
                .backupOnUpgrade(true)
                .sparrowYaml(sparrowYaml)
                .upgradePipeline(upgradePipeline)
                .build();
        this.configMapper = mapperFactory.create(ConfigDefinition.class, ConfigDefinition::new);
    }

    void reload() {
        try {
            ConfigDefinition loadedConfig = this.configMapper.load(this.configFilePath).value();
            loadedConfig.synchronization.map.validate();
            loadedConfig.synchronization.pdcMergeBlacklist = PDCMergeBlacklist.of(loadedConfig.synchronization.pdcMergeNamespaces);
            loadedConfig.synchronization.attributes.freeze();
            loadedConfig.synchronization.compiledSaveTriggers = SaveTriggers.of(loadedConfig.synchronization.saveTriggers);
            config = loadedConfig;
        } catch (Exception e) {
            this.plugin.logger().error("Failed to load " + CONFIG_FILE, e);
        }
    }

    // 配置文件
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConfigDefinition {
        @Comment("Do not modify this value")
        String configVersion = DependencyVersions.CONFIG_VERSION;

        @Comment("Enables or disables metrics collection via BStats")
        boolean metrics = true;

        @Comment("Enables automatic update checks")
        boolean updateChecker = true;

        @Comment({
                "Language of console messages, e.g. zh_cn",
                "Leave empty to follow the system locale, any locale without a translation file falls back to en"
        })
        String forcedLocale = "";

        @BlankLineBefore
        @Comment("Redis, backs the cross server session lock and messaging")
        RedisOptions redis = new RedisOptions();

        @BlankLineBefore
        @Comment("Where player snapshots are persisted")
        DatabaseOptions database = new DatabaseOptions();

        @BlankLineBefore
        @Comment("Synchronization settings")
        SynchronizationOptions synchronization = new SynchronizationOptions();

        @BlankLineBefore
        @Comment("Logging")
        LoggingOptions logging = new LoggingOptions();
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class LoggingOptions {
        @Comment({
                "Writes every plugin log line, including those hidden from the console,",
                "to a <date>.log file per day under the directory below",
                "Grep a player uuid there to get their full join/apply/save/quit timeline",
                "Read once at startup, reloading does not start or stop the writer"
        })
        boolean localFile = true;

        @Comment({
                "Where the log files go, resolved against the plugin data folder unless absolute"
        })
        String directory = "logs";

        @Comment({
                "Timestamp format of each log line, a java DateTimeFormatter pattern"
        })
        String timeFormat = "HH:mm:ss.SSS";

        @Comment({
                "Date format of the daily log file names, a java DateTimeFormatter pattern",
                "It decides when a new file starts, e.g. yyyy-MM would roll monthly instead of daily",
                "Closed files are compressed as <date>-<index>.log.gz, including an existing log on startup"
        })
        String fileDateFormat = "yyyy-MM-dd";

        @Comment({
                "Successful snapshot causes printed to the console",
                "Disconnect and command saves are enabled by default",
                "When local-file is enabled, every successful save is still written there"
        })
        ConsoleSaveCauses consoleSaveCauses = new ConsoleSaveCauses();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class ConsoleSaveCauses {
        boolean disconnect = true;
        boolean worldChange = false;
        boolean gameModeChange = false;
        boolean preDeath = false;
        boolean death = false;
        boolean shutdown = false;
        boolean worldSave = false;
        boolean command = true;
        boolean restore = false;
        boolean edit = false;
        boolean api = false;
        boolean unknown = false;

        boolean enabled(@NotNull SaveCause cause) {
            return switch (cause) {
                case DISCONNECT -> this.disconnect;
                case WORLD_CHANGE -> this.worldChange;
                case GAME_MODE_CHANGE -> this.gameModeChange;
                case PRE_DEATH -> this.preDeath;
                case DEATH -> this.death;
                case SHUTDOWN -> this.shutdown;
                case WORLD_SAVE -> this.worldSave;
                case COMMAND -> this.command;
                case RESTORE -> this.restore;
                case EDIT -> this.edit;
                case API -> this.api;
                case UNKNOWN -> this.unknown;
            };
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class SynchronizationOptions {
        @Comment({
                "Number of worker threads handling per-player tasks, rounded up to a power of two",
                "Tasks of one player always run on the same worker in submission order"
        })
        int workerThreads = 4;

        @Comment({
                "How long to wait for pending saves to reach the storage on shutdown",
                "Draining 1500 players at 4 workers takes about 8s when a storage write costs 20ms,",
                "and about 19s at 50ms, so 60 seconds leaves room for a remote or busy database",
                "A supervisor that stops the server sooner (Docker allows 10s by default)",
                "cuts the drain short no matter what is set here"
        })
        int shutdownTimeoutSeconds = 60;

        @Comment({
                "How many snapshots to keep per player, oldest unpinned ones are rotated out",
                "Pinned snapshots never count against this limit and are never rotated"
        })
        int maxSnapshots = 32;

        @Comment({
                "How many times a snapshot that could not reach the database is put back in the queue",
                "-1 keeps retrying until the database comes back, which is what you want on an outage",
                "A snapshot that runs out of attempts is written to disk instead, never dropped",
                "Snapshots that fail for reasons retrying cannot fix (too large, encoding errors) skip this entirely"
        })
        int maxSaveRetries = -1;

        @Comment({
                "How long the login gate waits for player data before giving up, in seconds",
                "A player whose data is not ready in time is disconnected, never let in unsynced"
        })
        int loginTimeoutSeconds = 60;

        @Comment({
                "How newly written snapshots are compressed, existing data stays readable whatever is set here",
                "Available: ZSTD, DEFLATE, NONE",
                "  ZSTD    - the fastest saves and loads at the best ratio (recommended)",
                "  DEFLATE - the JDK codec, needs no native library, use it if Zstd fails to load here",
                "  NONE    - plain bytes, note that a bigger snapshot also takes longer to reach the database"
        })
        CompressorRegistry compression = CompressorRegistry.ZSTD;

        @BlankLineBefore
        @Comment({
                "Built-in player data enabled for synchronization",
                "Read once during startup; changes require a server restart"
        })
        DataTypes dataTypes = new DataTypes();

        @BlankLineBefore
        @Comment("Map compilation and origin settings")
        MapOptions map = new MapOptions();

        @BlankLineBefore
        @Comment({
                "Writes compatible snapshot data into vanilla player data during the login gate",
                "Disable this on non-standard servers to apply every type during PlayerJoinEvent",
                "Reloading applies this option to login preparations started afterwards"
        })
        NativeAsyncApplyOptions nativeAsyncApply = new NativeAsyncApplyOptions();

        @BlankLineBefore
        @Comment("Advancements synchronization settings")
        AdvancementsOptions advancements = new AdvancementsOptions();

        @BlankLineBefore
        @Comment("Attribute synchronization settings")
        AttributeOptions attributes = new AttributeOptions();

        @BlankLineBefore
        @Comment("Save snapshots around player death")
        DeathTriggerOptions death = new DeathTriggerOptions();

        @BlankLineBefore
        @Comment({
                "Persistent data (PDC) merge blacklist; every entry is a path relative to custom_data",
                "Blacklisted paths are neither captured nor merged",
                "Use \"sparrow-sync-ignore\" for custom_data -> sparrow-sync-ignore",
                "Use [\"sparrow-sync\", \"ignore\"] for custom_data -> sparrow-sync -> ignore",
                "Reloading the plugin applies these paths to later captures and applications"
        })
        @AfterComment({
                "- sparrow-sync-ignore",
                "- [\"sparrow-sync\", \"ignore\"]"
        })
        List<Object> pdcMergeNamespaces = List.of();

        @BlankLineBefore
        @Comment({
                "Automatic snapshot save triggers",
                "Reloading the plugin applies these options to later trigger invocations"
        })
        SaveTriggerOptions saveTriggers = new SaveTriggerOptions();

        @YamlIgnore
        PDCMergeBlacklist pdcMergeBlacklist = PDCMergeBlacklist.empty();

        @YamlIgnore
        SaveTriggers compiledSaveTriggers = SaveTriggers.of(this.saveTriggers);
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MapOptions {
        @Comment({
                "Enables map compilation and decoding",
                "When disabled, snapshots pass through with every map item unchanged"
        })
        boolean enabled = false;

        @BlankLineBefore
        @Comment({
                "Mode assigned to maps when compiling snapshots on this server",
                "HIDE removes IDs until the map returns to its owner",
                "SYNC publishes source map data and installs persistent negative-ID replicas on other servers",
                "Maps already carrying a mode keep that mode when passing through another server",
                "Available: HIDE, SYNC",
                "Failed maps warn and pass through unchanged"
        })
        MapType type = MapType.HIDE;

        @BlankLineBefore
        @Comment({
                "Identifies the map data owned by this server",
                "Supported placeholders: ${server-id}, ${world-uuid}",
                "world-uuid is the UUID of the overworld, where the map data belongs",
                "Changing this ID treats previously compiled maps as foreign maps"
        })
        String mapOwnerId = "${server-id}-${world-uuid}";

        public boolean enabled() {
            return this.enabled;
        }

        @NotNull
        public MapType type() {
            return this.type;
        }

        @NotNull
        public String mapOwnerId() {
            return this.mapOwnerId;
        }

        /** 将来源模板中的服务器标识和主世界 UUID 替换为本服值. */
        @NotNull
        public String resolveOwnerId(@NotNull String serverId, @NotNull UUID worldUuid) {
            return this.mapOwnerId.replace("${server-id}", serverId).replace("${world-uuid}", worldUuid.toString());
        }

        private void validate() {
            if (!this.enabled) return;
            String literals = this.mapOwnerId.replace("${server-id}", "").replace("${world-uuid}", "");
            if (this.mapOwnerId.isBlank() || literals.contains("${")) {
                throw new IllegalArgumentException("map-owner-id must be non-blank and may only use ${server-id} and ${world-uuid}");
            }
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DataTypes {
        boolean advancements = true;
        boolean attributes = true;
        boolean enchantmentSeed = true;
        boolean enderChest = true;
        boolean experience = true;
        boolean flightStatus = true;
        boolean gameMode = true;
        boolean health = true;
        boolean hunger = true;
        boolean inventory = true;
        boolean location = false;
        boolean persistentData = true;
        boolean potionEffects = true;
        boolean statistics = true;

        public boolean advancements() {
            return this.advancements;
        }

        public boolean attributes() {
            return this.attributes;
        }

        public boolean enchantmentSeed() {
            return this.enchantmentSeed;
        }

        public boolean enderChest() {
            return this.enderChest;
        }

        public boolean experience() {
            return this.experience;
        }

        public boolean flightStatus() {
            return this.flightStatus;
        }

        public boolean gameMode() {
            return this.gameMode;
        }

        public boolean health() {
            return this.health;
        }

        public boolean hunger() {
            return this.hunger;
        }

        public boolean inventory() {
            return this.inventory;
        }

        public boolean location() {
            return this.location;
        }

        public boolean persistentData() {
            return this.persistentData;
        }

        public boolean potionEffects() {
            return this.potionEffects;
        }

        public boolean statistics() {
            return this.statistics;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class NativeAsyncApplyOptions {
        boolean playerData = true;
        boolean advancements = true;
        boolean statistics = true;

        public boolean playerData() {
            return this.playerData;
        }

        public boolean advancements() {
            return this.advancements;
        }

        public boolean statistics() {
            return this.statistics;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class AdvancementsOptions {
        @Comment({
                "Injects the PlayerAdvancements progressChanged tracker during PlayerJoinEvent",
                "Disable to use the regular full advancement capture path"
        })
        boolean injectProgressChanged = true;

        @Comment({
                "Keeps advancement progress for IDs the applying server does not register",
                "Native apply writes known IDs to vanilla JSON and hands unknown IDs to the join tracker",
                "Without native apply, the PlayerJoin path applies known IDs and keeps unknown IDs in the same tracker",
                "Disable when every server shares the same advancements; native JSON then skips the membership scan"
        })
        boolean keepUnknownAdvancements = true;

        public boolean injectProgressChanged() {
            return this.injectProgressChanged;
        }

        public boolean keepUnknownAdvancements() {
            return this.keepUnknownAdvancements;
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class AttributeOptions {
        @Comment({
                "Injects an onDirty consumer into configured AttributeInstance objects during PlayerJoinEvent",
                "Disable to use the regular full attribute capture path"
        })
        boolean injectConsumer = true;

        @Comment({
                "Attribute keys saved by attribute synchronization; supports * wildcard matching",
                "Both modern and legacy vanilla keys are listed across supported Minecraft versions",
                "Reloading the plugin applies attribute filters to later captures and applications"
        })
        List<String> whitelist = List.of(
                "minecraft:generic.max_health",
                "minecraft:max_health",
                "minecraft:generic.max_absorption",
                "minecraft:max_absorption",
                "minecraft:generic.luck",
                "minecraft:luck",
                "minecraft:generic.scale",
                "minecraft:scale",
                "minecraft:generic.step_height",
                "minecraft:step_height",
                "minecraft:generic.gravity",
                "minecraft:gravity"
        );

        @Comment("Attribute modifier keys kept local to each server; supports * wildcard matching")
        List<String> modifierBlacklist = List.of(
                "minecraft:effect.*",
                "minecraft:creative_mode_*"
        );

        @YamlIgnore
        private KeyPattern[] attributePatterns = compilePatterns(this.whitelist);
        @YamlIgnore
        private KeyPattern[] modifierPatterns = compilePatterns(this.modifierBlacklist);

        @NotNull
        public List<String> whitelist() {
            return this.whitelist;
        }

        public boolean injectConsumer() {
            return this.injectConsumer;
        }

        @NotNull
        public List<String> modifierBlacklist() {
            return this.modifierBlacklist;
        }

        /** 判断属性 key 是否在同步白名单内. */
        public boolean attributeAllowed(@NotNull String attribute) {
            String key = namespaced(attribute);
            for (int i = 0; i < this.attributePatterns.length; i++) {
                if (this.attributePatterns[i].matches(key)) return true;
            }
            return false;
        }

        /** 判断 modifier key 是否留在当前服务器. */
        public boolean modifierBlacklisted(@NotNull String modifier) {
            String key = namespaced(modifier);
            for (int i = 0; i < this.modifierPatterns.length; i++) {
                if (this.modifierPatterns[i].matches(key)) return true;
            }
            return false;
        }

        private void freeze() {
            this.whitelist = List.copyOf(this.whitelist);
            this.modifierBlacklist = List.copyOf(this.modifierBlacklist);
            this.attributePatterns = compilePatterns(this.whitelist);
            this.modifierPatterns = compilePatterns(this.modifierBlacklist);
        }

        private static KeyPattern[] compilePatterns(List<String> patterns) {
            KeyPattern[] compiled = new KeyPattern[patterns.size()];
            for (int i = 0; i < compiled.length; i++) {
                String pattern = namespaced(patterns.get(i));
                compiled[i] = new KeyPattern(pattern, pattern.indexOf('*'));
            }
            return compiled;
        }

        private static boolean matchesPattern(@NotNull String pattern, @NotNull String value) {
            int patternIndex = 0;
            int valueIndex = 0;
            int wildcardIndex = -1;
            int retryIndex = -1;
            while (valueIndex < value.length()) {
                if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                    patternIndex++;
                    valueIndex++;
                    continue;
                }
                if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                    wildcardIndex = patternIndex++;
                    retryIndex = valueIndex;
                    continue;
                }
                if (wildcardIndex < 0) return false;
                patternIndex = wildcardIndex + 1;
                valueIndex = ++retryIndex;
            }
            while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                patternIndex++;
            }
            return patternIndex == pattern.length();
        }

        @NotNull
        private static String namespaced(@NotNull String key) {
            return key.indexOf(':') < 0 ? "minecraft:" + key : key;
        }

        private record KeyPattern(String value, int wildcard) {
            private boolean matches(String key) {
                if (this.wildcard < 0) return this.value.equals(key);
                if (this.wildcard == this.value.length() - 1) return key.regionMatches(0, this.value, 0, this.wildcard);
                return matchesPattern(this.value, key);
            }
        }
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class SaveTriggerOptions {
        @Comment("Save after a player changes worlds")
        WorldChangeTriggerOptions worldChange = new WorldChangeTriggerOptions();

        @BlankLineBefore
        @Comment("Save players when their world is saved")
        WorldSaveTriggerOptions worldSave = new WorldSaveTriggerOptions();

        @BlankLineBefore
        @Comment("Save after a player's game mode changes")
        GameModeChangeTriggerOptions gameModeChange = new GameModeChangeTriggerOptions();

        @BlankLineBefore
        @Comment("Save on player death")
        DeathTriggerOptions death = new DeathTriggerOptions();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class WorldChangeTriggerOptions {
        @Comment("Whether changing worlds creates a snapshot")
        boolean enabled = false;

        @Comment("Do not save when the player leaves one of these worlds")
        List<String> ignoredFromWorlds = List.of();

        @Comment("Do not save when the player enters one of these worlds")
        List<String> ignoredToWorlds = List.of();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class WorldSaveTriggerOptions {
        @Comment("Whether saving a world creates snapshots for its active players")
        boolean enabled = true;
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class GameModeChangeTriggerOptions {
        @Comment("Whether changing game mode creates a snapshot")
        boolean enabled = true;

        @Comment({
                "Do not save when the player changes into one of these modes",
                "Available: SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR"
        })
        List<GameMode> ignoredTargetModes = List.of();
    }

    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DeathTriggerOptions {
        @Comment({
                "Save the complete state visible inside PlayerDeathEvent before Paper removes dropped items",
                "This is an additional history snapshot and is disabled by default"
        })
        boolean saveBeforeDeath = false;

        @Comment({
                "Save the real state on the next player tick after Paper applies keepInventory and itemsToKeep",
                "The snapshot includes the items that actually remain on the dead player"
        })
        boolean saveAfterDeath = true;

        @Comment("Do not create either death snapshot in these worlds")
        List<String> ignoredWorlds = List.of();
    }

    public record SaveTriggers(@NotNull WorldChangeTrigger worldChange,
                               @NotNull WorldSaveTrigger worldSave,
                               @NotNull GameModeChangeTrigger gameModeChange,
                               @NotNull DeathTrigger deathTrigger) {

        @NotNull
        private static SaveTriggers of(@NotNull SaveTriggerOptions options) {
            return new SaveTriggers(
                    new WorldChangeTrigger(options.worldChange.enabled, Set.copyOf(options.worldChange.ignoredFromWorlds), Set.copyOf(options.worldChange.ignoredToWorlds)),
                    new WorldSaveTrigger(options.worldSave.enabled),
                    new GameModeChangeTrigger(options.gameModeChange.enabled, Set.copyOf(options.gameModeChange.ignoredTargetModes)),
                    new DeathTrigger(options.death.saveBeforeDeath, options.death.saveAfterDeath, Set.copyOf(options.death.ignoredWorlds))
            );
        }
    }

    public record WorldChangeTrigger(boolean enabled, @NotNull Set<String> ignoredFromWorlds, @NotNull Set<String> ignoredToWorlds) {
    }

    public record WorldSaveTrigger(boolean enabled) {
    }

    public record GameModeChangeTrigger(boolean enabled, @NotNull Set<GameMode> ignoredTargetModes) {
    }

    public record DeathTrigger(boolean saveBeforeDeath, boolean saveAfterDeath, @NotNull Set<String> ignoredWorlds) {
    }

    /**
     * 相对于 {@code custom_data} 根节点的不可变黑名单路径树.
     */
    public static final class PDCMergeBlacklist {
        private static final PDCMergeBlacklist TERMINAL = new PDCMergeBlacklist(Map.of());
        private static final PDCMergeBlacklist EMPTY = new PDCMergeBlacklist(Map.of());

        private final Map<String, PDCMergeBlacklist> children;

        private PDCMergeBlacklist(Map<String, PDCMergeBlacklist> children) {
            this.children = children;
        }

        /**
         * 将字符串和分段列表编译为不可变路径树, 前缀路径覆盖其全部后代.
         *
         * @param entries YAML 中的黑名单条目
         * @return 可供采集与合并直接查询的路径树
         */
        @NotNull
        public static PDCMergeBlacklist of(@NotNull List<?> entries) {
            Builder root = new Builder();
            for (Object entry : entries) {
                if (entry instanceof String segment) {
                    root.add(segment);
                    continue;
                }
                if (entry instanceof List<?> path && !path.isEmpty()) {
                    root.add(path);
                    continue;
                }
                throw invalidEntry(entry);
            }
            return root.freeze();
        }

        public boolean terminal() {
            return this == TERMINAL;
        }

        public boolean hasChildren() {
            return !this.children.isEmpty();
        }

        @Nullable
        public PDCMergeBlacklist child(@NotNull String segment) {
            return this.children.get(segment);
        }

        private static PDCMergeBlacklist empty() {
            return EMPTY;
        }

        private static IllegalArgumentException invalidEntry(Object entry) {
            return new IllegalArgumentException("PDC merge blacklist entries must be a path string or a non-empty list of path strings: " + entry);
        }

        private static final class Builder {
            private boolean terminal;
            private Map<String, Builder> children;

            private void add(String segment) {
                if (this.children == null) {
                    this.children = new HashMap<>();
                }
                this.children.computeIfAbsent(segment, ignored -> new Builder()).finish();
            }

            private void add(List<?> path) {
                Builder current = this;
                for (Object element : path) {
                    if (!(element instanceof String segment)) {
                        throw invalidEntry(path);
                    }
                    if (current.terminal) return;
                    if (current.children == null) {
                        current.children = new HashMap<>();
                    }
                    current = current.children.computeIfAbsent(segment, ignored -> new Builder());
                }
                current.finish();
            }

            private void finish() {
                this.terminal = true;
                this.children = null;
            }

            private PDCMergeBlacklist freeze() {
                if (this.terminal) return TERMINAL;
                if (this.children == null) return EMPTY;
                Map<String, PDCMergeBlacklist> frozenChildren = new HashMap<>(this.children.size());
                for (Map.Entry<String, Builder> entry : this.children.entrySet()) {
                    frozenChildren.put(entry.getKey(), entry.getValue().freeze());
                }
                return new PDCMergeBlacklist(Map.copyOf(frozenChildren));
            }
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class RedisOptions {
        @Comment("Connection url, credentials go below instead of into the url")
        String url = "redis://localhost:6379";

        @Comment("Username, leave empty on a server without ACL users")
        String username = "";

        @Comment("Password, leave empty when the server requires none")
        String password = "";

        public String url() {
            return this.url;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class DatabaseOptions {
        @Comment({
                "Which backend keeps player snapshots, only the matching section below is read",
                "Available: MONGODB, MYSQL"
        })
        StorageType type = StorageType.MONGODB;

        @BlankLineBefore
        @Comment("Read when type is MYSQL")
        MysqlOptions mysql = new MysqlOptions();

        @BlankLineBefore
        @Comment("Read when type is MONGODB")
        MongoOptions mongodb = new MongoOptions();
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MongoOptions {
        @Comment("Connection url, credentials go below instead of into the url")
        String url = "mongodb://localhost:27017";

        @Comment("Database name")
        String database = "sparrow_sync";

        @Comment("Username, leave empty to connect without authentication")
        String username = "";

        @Comment("Password")
        String password = "";

        @Comment("Authentication source database")
        String authSource = "admin";

        @Comment("Prefix of every collection created by this plugin")
        String collectionPrefix = "sparrow_";

        // 配置映射走无参构造加字段注入, 全参构造供程序化装配和测试使用
        public MongoOptions() {
        }

        public MongoOptions(String url, String database, String username, String password, String authSource, String collectionPrefix) {
            this.url = url;
            this.database = database;
            this.username = username;
            this.password = password;
            this.authSource = authSource;
            this.collectionPrefix = collectionPrefix;
        }

        public String url() {
            return this.url;
        }

        public String database() {
            return this.database;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }

        public String authSource() {
            return this.authSource;
        }

        public String collectionPrefix() {
            return this.collectionPrefix;
        }
    }

    // 命名风格按类型解析而不从外层继承, 这里的注解决定本段的键名形式
    @Configuration(naming = Configuration.Naming.KEBAB_CASE)
    public static class MysqlOptions {
        @Comment("Connection url, credentials go below instead of into the url")
        String url = "jdbc:mysql://localhost:3306/sparrow_sync";

        @Comment("Username")
        String username = "root";

        @Comment("Password")
        String password = "";

        @Comment("Prefix of every table created by this plugin")
        String tablePrefix = "sparrow_";

        public String url() {
            return this.url;
        }

        public String username() {
            return this.username;
        }

        public String password() {
            return this.password;
        }

        public String tablePrefix() {
            return this.tablePrefix;
        }
    }



    // 读取一律经这里穿透到当前那份配置, 方法名以 $ 还原配置文件里的层级.
    // 同一次触发要读取的相关选项编译成一个不可变值, 调用方每次重新取得当前快照

    public static boolean checkUpdate() {
        return config.updateChecker;
    }

    public static boolean metrics() {
        return config.metrics;
    }

    @Nullable
    public static Locale forcedLocale() {
        return TranslationManager.parseLocale(config.forcedLocale);
    }

    public static boolean logging$consoleSave(@NotNull SaveCause cause) {
        return config.logging.consoleSaveCauses.enabled(cause);
    }

    public static boolean logging$localFile() {
        return config.logging.localFile;
    }

    public static String logging$directory() {
        return config.logging.directory;
    }

    public static String logging$timeFormat() {
        return config.logging.timeFormat;
    }

    public static String logging$fileDateFormat() {
        return config.logging.fileDateFormat;
    }

    public static int synchronization$workerThreads() {
        return config.synchronization.workerThreads;
    }

    public static int synchronization$shutdownTimeoutSeconds() {
        return config.synchronization.shutdownTimeoutSeconds;
    }

    public static int synchronization$loginTimeoutSeconds() {
        return config.synchronization.loginTimeoutSeconds;
    }

    @NotNull
    public static NativeAsyncApplyOptions synchronization$nativeAsyncApply() {
        return config.synchronization.nativeAsyncApply;
    }

    @NotNull
    public static MapOptions synchronization$map() {
        return config.synchronization.map;
    }

    @NotNull
    public static AdvancementsOptions synchronization$advancements() {
        return config.synchronization.advancements;
    }

    @NotNull
    public static SaveTriggers synchronization$saveTriggers() {
        return config.synchronization.compiledSaveTriggers;
    }

    public static int synchronization$maxSaveRetries() {
        return config.synchronization.maxSaveRetries;
    }

    public static int synchronization$maxSnapshots() {
        return config.synchronization.maxSnapshots;
    }

    public static CompressorRegistry synchronization$compression() {
        return config.synchronization.compression;
    }

    @NotNull
    public static DataTypes synchronization$dataTypes() {
        return config.synchronization.dataTypes;
    }

    @NotNull
    public static AttributeOptions synchronization$attributes() {
        return config.synchronization.attributes;
    }

    @NotNull
    public static PDCMergeBlacklist synchronization$pdcMergeNamespaces() {
        return config.synchronization.pdcMergeBlacklist;
    }

    public static StorageType database$type() {
        return config.database.type;
    }

    @NotNull
    public static MongoOptions database$mongodb() {
        return config.database.mongodb;
    }

    @NotNull
    public static MysqlOptions database$mysql() {
        return config.database.mysql;
    }

    @NotNull
    public static RedisOptions redis() {
        return config.redis;
    }
}
