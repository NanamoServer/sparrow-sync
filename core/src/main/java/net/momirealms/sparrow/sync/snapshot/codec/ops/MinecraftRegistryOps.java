package net.momirealms.sparrow.sync.snapshot.codec.ops;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
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
    private static volatile @Nullable RegistryOps<JsonElement> json;

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
                ops = RegistryOps.create(NBTOps.INSTANCE, MinecraftServer.getServer().registryAccess());
                sparrowNbt = ops;
            }
        }
        return ops;
    }

    /**
     * 返回读写 Gson JsonElement 的注册表 ops, 文本组件的 JSON 互转走这里.
     * @throws IllegalStateException 服务器未就绪时, 就绪后可以重试
     */
    @NotNull
    public static RegistryOps<JsonElement> json() {
        RegistryOps<JsonElement> ops = json;
        if (ops != null) return ops;
        synchronized (MinecraftRegistryOps.class) {
            ops = json;
            if (ops == null) {
                ops = RegistryOps.create(JsonOps.INSTANCE, MinecraftServer.getServer().registryAccess());
                json = ops;
            }
        }
        return ops;
    }
}
