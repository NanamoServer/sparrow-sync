package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.storage.PlayerDataStorage")
public interface PlayerDataStorageProxy {
    Class<?> CLASS = SparrowClass.find("net.minecraft.world.level.storage.PlayerDataStorage");
}
