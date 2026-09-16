package net.momirealms.sparrow.sync.util;

import com.google.gson.JsonElement;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.network.chat.ComponentSerialization;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.Tag;
import net.momirealms.sparrow.nbt.adventure.NBTDataComponentValue;
import net.momirealms.sparrow.sync.snapshot.codec.ops.MinecraftRegistryOps;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MinecraftComponents {
    private MinecraftComponents() {}

    /**
     * 把插件自带的 Adventure 组件转成服务端原版组件. 转换只读注册表, 可以在任意线程调用.
     *
     * @throws IllegalArgumentException 组件无法按当前版本的文本格式解析时
     */
    @NotNull
    public static net.minecraft.network.chat.Component fromAdventure(@NotNull Component component) {
        JsonElement json = GsonHelper.get().fromJson(AdventureHelper.componentToJson(component), JsonElement.class);
        return ComponentSerialization.CODEC
                .parse(MinecraftRegistryOps.json(), json)
                .getOrThrow(message -> new IllegalArgumentException("failed to convert component: " + message));
    }

    /**
     * 把服务端原版组件转成插件自带的 Adventure 组件.
     *
     * @throws IllegalArgumentException 组件无法按当前版本的文本格式序列化时
     */
    @NotNull
    public static Component toAdventure(@NotNull net.minecraft.network.chat.Component component) {
        JsonElement json = ComponentSerialization.CODEC
                .encodeStart(MinecraftRegistryOps.json(), component)
                .getOrThrow(message -> new IllegalArgumentException("failed to convert component: " + message));
        return AdventureHelper.jsonToComponent(GsonHelper.get().toJson(json));
    }
}
