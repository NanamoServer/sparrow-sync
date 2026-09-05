package net.momirealms.sparrow.sync.snapshot.data;

import net.minecraft.nbt.CompoundTag;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 可以在登录 Gate worker 准备原版登录数据的类型.
 * 实现不得读取玩家或派发 Bukkit 事件; 所需全局元数据必须通过线程安全的快照读取.
 *
 * @param <T> 解码后的值类型
 */
@ApiStatus.Internal
public interface NativePlayerDataType<T> extends PlayerDataType<T> {

    /**
     * 是否应该执行 applyNative, 主要用于检查配置选项开关和不符合条件的服务端跳过.
     */
    boolean shouldApply();

    /**
     * 把本类型安装到原版登录读取的数据源.
     * <strong>返回 {@link NativeApplyResult#NOT_APPLIED} 或抛出异常时不得留下部分可见的写入</strong>.
     *
     * @return 应用目标与可选的 Join 回调, 用于更新槽位状态和发布 synthetic player data
     * @throws IOException 当外部原生数据源写入失败时
     */
    @NotNull
    NativeApplyResult applyNative(@NotNull UUID player, @NotNull CompoundTag playerData, @NotNull T value) throws IOException;

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
