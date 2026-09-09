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
 * 可以在登录拦截阶段的异步线程准备登录数据源的类型.
 * 实现不得读取玩家或派发 Bukkit 事件; 所需全局元数据必须通过线程安全的快照读取.
 *
 * @param <T> 解码后的值类型
 */
@ApiStatus.Internal
public interface NativePlayerDataType<T> extends PlayerDataType<T> {

    /**
     * 根据配置、服务端能力和会话绑定的登录连接判断是否执行 applyNative.
     * <strong>调用期间保持登录拦截状态</strong>, 返回 false 的数据留到 Join 应用.
     */
    boolean shouldApply(@NotNull PlayerSession session);

    /**
     * 把本类型的数据写入原版登录读取的数据源.
     * playerData 是各类型共用的 SparrowNBT 工作副本, 流水线完成后转为原版 NBT.
     * <strong>返回 {@link NativeApplyResult#NOT_APPLIED} 或抛出异常时不得留下部分可见的写入</strong>.
     *
     * @return 应用目标与可选的 Join 回调, 用于更新槽位状态和提供合成登录 NBT
     * @throws IOException 当外部登录数据源写入失败时
     */
    @NotNull
    NativeApplyResult applyNative(@NotNull PlayerSession session, @NotNull CompoundTag playerData, @NotNull T value) throws IOException;

    /** joinHandoff 在玩家线程按类型依赖顺序执行一次, 执行后随槽位释放. */
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
