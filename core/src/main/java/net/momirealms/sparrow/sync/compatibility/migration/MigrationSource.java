package net.momirealms.sparrow.sync.compatibility.migration;

import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.storage.StoredUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@ApiStatus.Internal
public interface MigrationSource {

    @NotNull
    String id();

    void read(@NotNull Sink sink) throws Exception;

    interface Sink {

        /**
         * 接收一名玩家已经转换好的 Sparrow 字段, 由迁移端分配本包内的快照身份.
         *
         * @param data 每玩家交付一次, 调用期间 Tag 保持稳定.
         * @throws IOException ZIP 写入或异常归档失败, 来源应立即结束读取
         */
        void accept(@NotNull PlayerData data) throws IOException;

        /**
         * 归档一名玩家的数据故障, 归档完成后允许继续交付其他玩家.
         *
         * @param player 失败记录所属的玩家 UUID
         * @param name 源插件提供的玩家名, 不可得时为 null
         * @param stage 发生故障的读取、解码或字段转换步骤
         * @param failure 已确认为单玩家数据问题的异常; 连接中断等整体故障应由 read 抛出
         * @param raw 源插件提供的原始载体, 不可得时为 null
         * @throws IOException 异常归档失败, 此时记录尚未计入已跳过数量
         */
        void reject(@NotNull UUID player, @Nullable String name, @NotNull String stage, @NotNull Throwable failure, byte @Nullable [] raw) throws IOException;
    }

    record PlayerData(@NotNull UUID player, @Nullable StoredUser user, @Nullable Long timestamp, int mcDataVersion, @NotNull Map<DataKey, Tag> data) {
    }
}
