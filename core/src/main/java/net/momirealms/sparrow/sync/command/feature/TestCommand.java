package net.momirealms.sparrow.sync.command.feature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.DoubleTag;
import net.momirealms.sparrow.nbt.FloatTag;
import net.momirealms.sparrow.nbt.ListTag;
import net.momirealms.sparrow.nbt.NumericTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.command.BukkitCommandFeature;
import net.momirealms.sparrow.sync.command.CommandManager;
import net.momirealms.sparrow.sync.snapshot.codec.BinarySnapshotCodec;
import net.momirealms.sparrow.sync.snapshot.codec.DecodedSnapshot;
import net.momirealms.sparrow.sync.snapshot.codec.DocumentSnapshotCodec;
import net.momirealms.sparrow.sync.configuration.PluginConfig;
import net.momirealms.sparrow.sync.data.SnapshotApplier;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.session.SnapshotService;
import net.momirealms.sparrow.sync.storage.StorageProvider;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.snapshot.DataKey;
import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.snapshot.Snapshot;
import net.momirealms.sparrow.sync.snapshot.SnapshotMeta;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.incendo.cloud.Command;
import org.incendo.cloud.bukkit.parser.PlayerParser;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.EnumParser;
import org.incendo.cloud.parser.standard.IntegerParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 通用真机测试命令, 按参数分发到各测试项, 输出为纯技术文本不进翻译体系.
 * 新测试项只需增加 {@link TestArgument} 枚举值并接上对应方法.
 */
public final class TestCommand extends BukkitCommandFeature {
    private static final NamespacedKey SMOKE_MARKER = new NamespacedKey("sparrow_sync", "smoke");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public TestCommand(CommandManager commandManager, SparrowSync plugin) {
        super(commandManager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .required("case", EnumParser.enumParser(TestArgument.class))
                .optional("player", PlayerParser.playerParser())
                .optional("count", IntegerParser.integerParser(1, 200))
                .handler(context -> {
                    TestArgument argument = context.get("case");
                    switch (argument) {
                        case SMOKE -> this.smokeTest(context);
                        case SAVE -> this.saveTest(context);
                        case LOAD -> this.loadTest(context);
                        case BURST -> this.burstTest(context);
                        case LIST -> this.listTest(context);
                        case PIN -> this.pinTest(context, true);
                        case UNPIN -> this.pinTest(context, false);
                        case ROTATE -> this.rotateTest(context);
                        case KILL -> this.killTest(context);
                        case REVIVE -> this.reviveTest(context);
                    }
                });
    }

    @Override
    public String getFeatureID() {
        return "test";
    }

    public enum TestArgument {
        SMOKE,
        SAVE,       // 立即采集并落库一次, 不用退服
        LOAD,       // 读库最新快照并应用, 不用重进
        BURST,      // 同 tick 连发 count 次保存, 压 timestamp 钳制与提交序
        LIST,       // 列出最近 count 份快照元数据, 与数据库对照
        PIN,        // 固定最新一份, 配合 ROTATE 验证豁免
        UNPIN,
        ROTATE,     // 手动触发一次轮转
        KILL,       // 静默杀死: 纯状态写入不走死亡流程, 构造/演示 Q8 的"快照死"象限
        REVIVE      // 从死亡状态经正常重生流程复活, 便于反复测试
    }

    // ---- SMOKE: 对执行者走一遍完整链路 —— 采集, 双形态编解码往返, 扰动玩家状态, 应用还原, 再采集逐类型比对.
    // 全程在同一 tick 内完成 (药水时长等不会跳变), 失败时以原始快照尽力恢复.
    // 测试会短暂清空并恢复执行者的全部同步数据, 仅供开发验证使用.

    private void smokeTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        if (target == null) return;
        // 冒烟必须在目标玩家的拥有线程上执行, 控制台命令 (Folia 上为全局线程) 经实体调度器转入
        target.getScheduler().run(plugin().javaPlugin(), task -> {
            List<String> report = this.runSmoke(target);
            for (int i = 0; i < report.size(); i++) {
                String line = report.get(i);
                sender.sendMessage(Component.text(line, line.startsWith("[FAIL]") || line.startsWith("!!") ? NamedTextColor.RED : NamedTextColor.GREEN));
            }
        }, null);
    }

    private List<String> runSmoke(Player player) {
        List<String> report = new ArrayList<>();
        // 用插件正式装配的那一套, 冒烟覆盖的就是运行期真正生效的类型集合 (含第三方注册的)
        SnapshotApplier applier = plugin().snapshotApplier();
        if (applier == null) {
            report.add("[FAIL] data registry is not assembled yet");
            return summarize(report);
        }
        // 与正式装配同一配置来源, 冒烟覆盖的是用户实际选用的压缩器
        BinarySnapshotCodec binaryCodec = new BinarySnapshotCodec(PluginConfig.synchronization$compression());
        DocumentSnapshotCodec documentCodec = new DocumentSnapshotCodec(plugin().dataRegistry(), binaryCodec);

        try {
            // 采集原始快照
            if (!(applier.capture(player) instanceof SnapshotApplier.CaptureResult.Ready captured)) {
                check(report, "capture", false, "critical data could not be captured");
                return summarize(report);
            }
            Map<DataKey, Tag> originalData = captured.data();
            Snapshot original = new Snapshot(smokeMeta(player), originalData);
            check(report, "capture (" + originalData.size() + " types, " + captured.skipped().size() + " skipped)", true, "");

            // 二进制形态逐位往返
            Snapshot roundTripped = original;
            DecodedSnapshot binaryDecoded = binaryCodec.decode(binaryCodec.encode(original));
            if (binaryDecoded instanceof DecodedSnapshot.Valid valid) {
                check(report, "binary round-trip", valid.snapshot().equals(original), "decoded snapshot differs");
            } else {
                check(report, "binary round-trip", false, String.valueOf(binaryDecoded));
            }
            // 文档形态的窄数值会升宽 (byte/short -> int, float -> double), 按语义等价比较而非严格 equals
            DecodedSnapshot documentDecoded = documentCodec.decode(documentCodec.encode(original));
            if (documentDecoded instanceof DecodedSnapshot.Valid valid) {
                List<DataKey> differing = diffKeys(original, valid.snapshot());
                check(report, "document round-trip (semantic)", differing.isEmpty(), "differs at " + differing);
                roundTripped = valid.snapshot();
            } else {
                check(report, "document round-trip (semantic)", false, String.valueOf(documentDecoded));
            }

            // 预解码走完整链路的快照, 关键失败则不扰动直接终止
            SnapshotApplier.PreparedSnapshot prepared = applier.prepare(roundTripped);
            if (!(prepared instanceof SnapshotApplier.PreparedSnapshot.Ready ready)) {
                check(report, "prepare", false, String.valueOf(prepared));
                return summarize(report);
            }
            check(report, "prepare", true, "");

            // 扰动后应用还原, 应用失败时再试一次尽力恢复
            disturb(player);
            SnapshotApplier.ApplyResult result = applier.apply(player, ready);
            if (!(result instanceof SnapshotApplier.ApplyResult.Success)) {
                check(report, "apply", false, String.valueOf(result));
                applier.apply(player, ready);
                report.add("!! player state may be disturbed, rejoin to be safe");
                return summarize(report);
            }
            check(report, "apply", true, "");
            boolean markerPreserved = player.getPersistentDataContainer().has(SMOKE_MARKER, PersistentDataType.INTEGER);
            check(report, "pdc merge preserves local-only key", markerPreserved, markerPreserved ? "" : "smoke marker was removed");
            player.getPersistentDataContainer().remove(SMOKE_MARKER);

            // 复采集并逐类型比对
            if (!(applier.capture(player) instanceof SnapshotApplier.CaptureResult.Ready recaptured)) {
                check(report, "re-capture", false, "critical data could not be captured");
                return summarize(report);
            }
            Map<DataKey, Tag> after = recaptured.data();
            for (Map.Entry<DataKey, Tag> entry : originalData.entrySet()) {
                Tag restored = after.get(entry.getKey());
                boolean equal = Objects.equals(entry.getValue(), restored);
                check(report, entry.getKey().asString(), equal, equal ? "" : "before=" + brief(entry.getValue()) + " after=" + brief(restored));
            }
        } catch (Exception exception) {
            report.add("[FAIL] unexpected: " + exception);
            plugin().logger().warn("Smoke test failed for " + player.getName(), exception);
        } finally {
            // 失败分支也清理本轮 PDC 标记
            player.getPersistentDataContainer().remove(SMOKE_MARKER);
        }
        return summarize(report);
    }

    // 打乱八类数据. PDC 标记验证增量合并会保留本服独有键, 其余扰动应由快照还原
    private static void disturb(Player player) {
        player.getInventory().clear();
        player.setItemOnCursor(null);
        player.getInventory().setHeldItemSlot((player.getInventory().getHeldItemSlot() + 1) % 9);
        player.getEnderChest().clear();
        player.clearActivePotionEffects();
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0));
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0f);
        player.setFoodLevel(1);
        player.setSaturation(0.0f);
        player.setExhaustion(0.0f);
        player.setHealth(Math.max(1.0, player.getHealth() / 2));
        player.setGameMode(player.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : GameMode.SPECTATOR);
        player.getPersistentDataContainer().set(SMOKE_MARKER, PersistentDataType.INTEGER, 1);
    }

    private static SnapshotMeta smokeMeta(Player player) {
        return SnapshotMeta.builder()
                .player(player.getUniqueId())
                .timestamp(System.currentTimeMillis())
                .cause(SaveCause.COMMAND)
                .server("smoke-test")
                .mcDataVersion(VersionHelper.WORLD_VERSION)
                .build();
    }

    // 语义不等的数据 key 清单, meta 不等时以哨兵 key 表示
    private static List<DataKey> diffKeys(Snapshot expected, Snapshot actual) {
        List<DataKey> differing = new ArrayList<>();
        if (!expected.meta().equals(actual.meta())) {
            differing.add(DataKey.sparrow("meta"));
        }
        for (Map.Entry<DataKey, Tag> entry : expected.data().entrySet()) {
            if (!semanticEquals(entry.getValue(), actual.data(entry.getKey()))) {
                differing.add(entry.getKey());
            }
        }
        if (expected.data().size() != actual.data().size()) {
            differing.add(DataKey.sparrow("extra_keys"));
        }
        return differing;
    }

    // 数值按值比较容忍升宽, 结构递归, 其余按 equals
    private static boolean semanticEquals(Tag expected, Tag actual) {
        if (Objects.equals(expected, actual)) return true;
        if (expected == null || actual == null) return false;
        if (expected instanceof NumericTag numericA && actual instanceof NumericTag numericB) {
            if (expected instanceof FloatTag || expected instanceof DoubleTag || actual instanceof FloatTag || actual instanceof DoubleTag) {
                return numericA.getAsDouble() == numericB.getAsDouble();
            }
            return numericA.getAsLong() == numericB.getAsLong();
        }
        if (expected instanceof CompoundTag compoundA && actual instanceof CompoundTag compoundB) {
            if (!compoundA.keySet().equals(compoundB.keySet())) return false;
            for (String key : compoundA.keySet()) {
                if (!semanticEquals(compoundA.get(key), compoundB.get(key))) return false;
            }
            return true;
        }
        if (expected instanceof ListTag listA && actual instanceof ListTag listB) {
            if (listA.size() != listB.size()) return false;
            for (int i = 0; i < listA.size(); i++) {
                if (!semanticEquals(listA.get(i), listB.get(i))) return false;
            }
            return true;
        }
        return false;
    }

    private static void check(List<String> report, String name, boolean passed, String detail) {
        if (passed) {
            report.add("[PASS] " + name);
        } else {
            report.add("[FAIL] " + name + (detail.isEmpty() ? "" : " - " + detail));
        }
    }

    private static String brief(Object value) {
        String text = String.valueOf(value);
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    private static List<String> summarize(List<String> report) {
        int passed = 0;
        for (int i = 0; i < report.size(); i++) {
            if (report.get(i).startsWith("[PASS]")) passed++;
        }
        report.add("smoke test finished: " + passed + " passed, " + (report.size() - passed) + " failed");
        return report;
    }

    // ---- SAVE / LOAD / BURST / LIST / PIN / ROTATE: 存储纵线的手动触发与验收, 不用退服重进即可对照数据库观察.

    private void saveTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        SnapshotService service = this.readyService(sender);
        if (target == null || service == null) return;
        target.getScheduler().run(plugin().javaPlugin(), task -> {
            long start = System.nanoTime();
            service.captureAndSave(target, SaveCause.COMMAND).whenComplete((result, throwable) -> {
                if (throwable != null) {
                    send(sender, "[FAIL] save: " + throwable, false);
                } else {
                    send(sender, "[PASS] save " + result + " in " + elapsed(start), true);
                }
            });
        }, null);
    }

    private void loadTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        SnapshotService service = this.readyService(sender);
        if (target == null || service == null) return;
        long start = System.nanoTime();
        service.loadAndApply(target).whenComplete((outcome, throwable) -> {
            if (throwable != null) {
                send(sender, "[FAIL] load: " + throwable, false);
                return;
            }
            switch (outcome) {
                case SnapshotService.LoadOutcome.Applied applied ->
                        send(sender, "[PASS] applied " + applied.applied() + " type(s), " + applied.skipped() + " skipped, in " + elapsed(start), true);
                case SnapshotService.LoadOutcome.Empty ignored -> send(sender, "[PASS] no snapshot in storage, nothing applied", true);
                case SnapshotService.LoadOutcome.Gone ignored -> send(sender, "[FAIL] player left before the apply stage", false);
                case SnapshotService.LoadOutcome.Failed failed -> send(sender, "[FAIL] " + failed.detail(), false);
            }
        });
    }

    // 同一 tick 内连发 count 份, timestamp 钳制应给出严格递增序, 且没有一份被判乱序
    private void burstTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        SnapshotService service = this.readyService(sender);
        if (target == null || service == null) return;
        int count = context.<Integer>optional("count").orElse(20);
        target.getScheduler().run(plugin().javaPlugin(), task -> {
            List<CompletableFuture<SaveResult>> saves = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                saves.add(service.captureAndSave(target, SaveCause.COMMAND));
            }
            CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).whenComplete((ignored, allThrowable) -> {
                int saved = 0;
                int outOfOrder = 0;
                int failed = 0;
                for (int i = 0; i < saves.size(); i++) {
                    try {
                        SaveResult result = saves.get(i).join();
                        if (result == SaveResult.SAVED) saved++;
                        else if (result == SaveResult.SAVED_OUT_OF_ORDER) outOfOrder++;
                    } catch (RuntimeException exception) {
                        failed++;
                    }
                }
                boolean clean = outOfOrder == 0 && failed == 0;
                send(sender, (clean ? "[PASS] " : "[FAIL] ") + "burst " + count + ": SAVED=" + saved + ", OUT_OF_ORDER=" + outOfOrder + ", failed=" + failed, clean);
                this.verifyBurstOrder(sender, target, count);
            });
        }, null);
    }

    // 落库后按最新的 count 份复核时间戳单调性 (轮转可能已裁掉更早的部分)
    private void verifyBurstOrder(CommandSender sender, Player target, int count) {
        plugin().storageProvider().listRecentSnapshots(target.getUniqueId(), count).whenComplete((metas, throwable) -> {
            if (throwable != null) {
                send(sender, "[FAIL] list after burst: " + throwable, false);
                return;
            }
            boolean strict = true;
            for (int i = 0; i + 1 < metas.size(); i++) {
                if (metas.get(i).timestamp() <= metas.get(i + 1).timestamp()) {
                    strict = false;
                    send(sender, "[FAIL] ts not strictly increasing: #" + i + "=" + metas.get(i).timestamp() + " vs #" + (i + 1) + "=" + metas.get(i + 1).timestamp(), false);
                }
            }
            if (strict) {
                long span = metas.isEmpty() ? 0 : metas.getFirst().timestamp() - metas.getLast().timestamp();
                send(sender, "[PASS] " + metas.size() + " snapshot(s) kept, timestamps strictly increasing, span " + span + "ms", true);
            }
        });
    }

    private void listTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        StorageProvider storage = this.readyStorage(sender);
        if (target == null || storage == null) return;
        int count = context.<Integer>optional("count").orElse(10);
        storage.listRecentSnapshots(target.getUniqueId(), count).whenComplete((metas, throwable) -> {
            if (throwable != null) {
                send(sender, "[FAIL] list: " + throwable, false);
                return;
            }
            if (metas.isEmpty()) {
                send(sender, "[PASS] no snapshots stored for " + target.getName(), true);
                return;
            }
            send(sender, "latest " + metas.size() + " snapshot(s) of " + target.getName() + ", newest first:", true);
            for (int i = 0; i < metas.size(); i++) {
                SnapshotMeta meta = metas.get(i);
                send(sender, "  " + shortId(meta.id()) + "  " + TIME_FORMAT.format(Instant.ofEpochMilli(meta.timestamp()))
                        + "  " + meta.cause() + "  @" + meta.server() + (meta.pinned() ? "  [PINNED]" : ""), true);
            }
        });
    }

    private void pinTest(CommandContext<CommandSender> context, boolean pinned) {
        CommandSender sender = context.sender();
        Player target = target(context);
        StorageProvider storage = this.readyStorage(sender);
        if (target == null || storage == null) return;
        storage.listRecentSnapshots(target.getUniqueId(), 1).thenCompose(metas -> {
            if (metas.isEmpty()) {
                send(sender, "[FAIL] no snapshot to " + (pinned ? "pin" : "unpin"), false);
                return CompletableFuture.completedFuture(null);
            }
            UUID id = metas.getFirst().id();
            return storage.setPinned(id, pinned).thenAccept(changed ->
                    send(sender, "[PASS] " + (pinned ? "pinned " : "unpinned ") + shortId(id) + " (changed=" + changed + ")", true));
        }).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                send(sender, "[FAIL] " + (pinned ? "pin" : "unpin") + ": " + throwable, false);
            }
        });
    }

    private void rotateTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        StorageProvider storage = this.readyStorage(sender);
        if (target == null || storage == null) return;
        int maxUnpinned = PluginConfig.synchronization$maxSnapshots();
        storage.rotate(target.getUniqueId(), maxUnpinned).whenComplete((deleted, throwable) -> {
            if (throwable != null) {
                send(sender, "[FAIL] rotate: " + throwable, false);
            } else {
                send(sender, "[PASS] rotate kept newest " + maxUnpinned + " unpinned, deleted " + deleted, true);
            }
        });
    }

    // 目标默认为执行者自己, 控制台必须显式指定
    private static Player target(CommandContext<CommandSender> context) {
        Player target = context.<Player>optional("player").orElse(context.sender() instanceof Player self ? self : null);
        if (target == null) {
            context.sender().sendMessage(Component.text("console must specify a player: /sparrow-sync test <case> <player>", NamedTextColor.RED));
        }
        return target;
    }

    // ---- KILL / REVIVE: Q8 四象限的真机验收原语, 静默改写死活状态以构造各象限的初态.

    // 与 HealthDataType 的"快照死"分支同一套写入: 若它正确, 这里也应当无死亡消息, 无掉落, 且重生按钮可用
    private void killTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        if (target == null) return;
        target.getScheduler().run(plugin().javaPlugin(), task -> {
            CraftPlayer craft = (CraftPlayer) target;
            craft.setRealHealth(0.0);
            craft.updateScaledHealth(true);
            send(sender, "[PASS] silently killed " + target.getName() + ", expect: death screen, working respawn button, no death message, no drops", true);
        }, null);
    }

    private void reviveTest(CommandContext<CommandSender> context) {
        CommandSender sender = context.sender();
        Player target = target(context);
        if (target == null) return;
        target.getScheduler().run(plugin().javaPlugin(), task -> {
            if (target.getHealth() > 0.0) {
                send(sender, "[PASS] " + target.getName() + " is alive, nothing to revive", true);
                return;
            }
            // 在线玩家的客户端可能停在死亡界面, 必须走正常重生流程而不是纯状态写入 (Q8 的 restore 警告)
            target.spigot().respawn();
            send(sender, "[PASS] respawned " + target.getName() + " through the normal respawn flow", true);
        }, null);
    }

    private SnapshotService readyService(CommandSender sender) {
        SnapshotService service = plugin().snapshotService();
        if (service == null) {
            send(sender, "[FAIL] snapshot service is not assembled yet", false);
        }
        return service;
    }

    private StorageProvider readyStorage(CommandSender sender) {
        StorageProvider storage = plugin().storageProvider();
        if (storage == null) {
            send(sender, "[FAIL] storage is not set up", false);
        }
        return storage;
    }

    private static void send(CommandSender sender, String line, boolean ok) {
        sender.sendMessage(Component.text(line, ok ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private static String elapsed(long startNanos) {
        return String.format(Locale.ROOT, "%.1fms", (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
