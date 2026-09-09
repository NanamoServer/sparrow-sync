package net.momirealms.sparrow.sync.plugin.command.feature;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.plugin.command.CommandManager;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.Command;
import org.incendo.cloud.parser.standard.StringParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DumpAllCommand extends AbstractDumpCommand {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX").withZone(ZoneId.systemDefault());

    public DumpAllCommand(CommandManager manager, SparrowSync plugin) {
        super(manager, plugin);
    }

    @Override
    public Command.Builder<? extends CommandSender> assembleCommand(org.incendo.cloud.CommandManager<CommandSender> manager, Command.Builder<CommandSender> builder) {
        return builder
                .optional("file", StringParser.greedyStringParser())
                .handler(context -> {
                    long before = System.currentTimeMillis();
                    String name = context.<String>optional("file").orElseGet(() -> "sparrow-sync-dump-" + FILE_TIME.format(Instant.ofEpochMilli(before)) + ".zip");
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name += ".zip";
                    String target = name;
                    this.handleFeedback(context, Component.translatable().key("command.dump_all.started"), Component.text(DISPLAY_TIME.format(Instant.ofEpochMilli(before))));
                    this.run(context, dump -> dump.dump(target, before));
                });
    }

    @Override
    public String getFeatureID() {
        return "dump_all";
    }
}
