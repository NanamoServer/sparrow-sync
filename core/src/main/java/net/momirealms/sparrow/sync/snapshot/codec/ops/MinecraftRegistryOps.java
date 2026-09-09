package net.momirealms.sparrow.sync.snapshot.codec.ops;

import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 带注册表访问的 DynamicOps 装配点. 物品编解码需要注册表解析附魔, 药水等按 id 引用的组件.
 * <strong>仅在服务器启动完成后调用</strong>, 首次访问时惰性绑定当前服务器的注册表.
 */
public final class MinecraftRegistryOps {
    private static volatile @Nullable RegistryOps<Tag> sparrowNbt;

    private MinecraftRegistryOps() {
    }

    /**
     * sparrow NBT 域的注册表 ops, 物品 CODEC 编解码用它直接产出 sparrow Tag.
     *
     * @throws IllegalStateException 服务器尚未就绪时, 该失败不缓存, 就绪后重试即可
     */
    @NotNull
    public static RegistryOps<Tag> sparrowNbt() {
        RegistryOps<Tag> ops = sparrowNbt;
        if (ops != null) return ops;
        synchronized (MinecraftRegistryOps.class) {
            ops = sparrowNbt;
            if (ops == null) {
                // 显式检查而不用类初始化持有, 服务器未就绪的失败不会永久毒化本类
                MinecraftServer server = MinecraftServer.getServer();
                if (server == null) {
                    throw new IllegalStateException("cannot bind registry ops before the server is ready");
                }
                ops = RegistryOps.create(NBTOps.INSTANCE, server.registryAccess());
                sparrowNbt = ops;
            }
        }
        return ops;
    }
}
