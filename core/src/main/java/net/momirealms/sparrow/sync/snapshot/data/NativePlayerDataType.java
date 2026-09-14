package net.momirealms.sparrow.sync.snapshot.data;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.sync.session.PlayerSession;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 在登录拦截阶段异步准备原版数据源的类型.
 * <strong>不得读取玩家或发送 Bukkit 事件</strong>, 全局数据须通过线程安全的快照读取.
 * @param <T> 解码后的值类型
 */
@ApiStatus.Internal
public interface NativePlayerDataType<T> extends PlayerDataType<T> {

    /**
     * 根据配置、服务端能力和本次连接判断能否提前应用.
     * <strong>调用期间须保持登录拦截</strong>, 返回 false 时留到 Join 处理.
     */
    boolean shouldApply(@NotNull PlayerSession session);

    /**
     * 写入原版登录使用的数据源, playerData 是各类型共用的 SparrowNBT 副本.
     * <strong>返回 NOT_APPLIED 或抛异常时不得留下部分可见的写入</strong>.
     * @return 写入目标和可选的 Join 回调
     * @throws IOException 外部数据源写入失败时
     */
    @NotNull
    NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull T value) throws IOException;

    /** joinHandoff 在玩家线程按依赖顺序执行一次, 随后释放. */
    record NativeApplyResult(@NotNull Target target, @Nullable Consumer<Player> joinHandoff) {
        public static final NativeApplyResult NOT_APPLIED = new NativeApplyResult(Target.NOT_APPLIED, null);
        public static final NativeApplyResult APPLIED_PLAYER_DATA = new NativeApplyResult(Target.APPLIED, null);
        public static final NativeApplyResult APPLIED_EXTERNAL = new NativeApplyResult(Target.EXTERNAL, null);

        @NotNull
        public NativeApplyResult withHandoff(@NotNull Consumer<Player> joinHandoff) {
            return new NativeApplyResult(this.target, joinHandoff);
        }

        enum Target {
            NOT_APPLIED,
            APPLIED,
            EXTERNAL
        }
    }
}
