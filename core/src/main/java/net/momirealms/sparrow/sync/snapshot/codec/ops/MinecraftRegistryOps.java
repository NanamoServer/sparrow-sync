package net.momirealms.sparrow.sync.snapshot.codec.ops;

import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 提供带 Minecraft 注册表的 DynamicOps, 供物品等数据解析注册表引用.
 * <strong>仅在服务器就绪后调用</strong>, 首次访问时绑定注册表.
 */
public final class MinecraftRegistryOps {
    private static volatile @Nullable RegistryOps<Tag> sparrowNbt;

    private MinecraftRegistryOps() {
    }

    /**
     * 返回直接读写 Sparrow Tag 的注册表 ops.
     * @throws IllegalStateException 服务器未就绪时, 就绪后可以重试
     */
    @NotNull
    public static RegistryOps<Tag> sparrowNbt() {
        RegistryOps<Tag> ops = sparrowNbt;
        if (ops != null) return ops;
        synchronized (MinecraftRegistryOps.class) {
            ops = sparrowNbt;
            if (ops == null) {
                // 仅在服务器就绪后缓存 ops, 初始化失败时允许重试
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
