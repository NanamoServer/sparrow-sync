package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;
import net.momirealms.sparrow.sync.proxy.minecraft.resources.IdentifierProxy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@ReflectionProxy(name = {"net.minecraft.world.level.storage.SavedDataStorage", "net.minecraft.world.level.storage.DimensionDataStorage"})
public interface SavedDataStorageProxy {
    SavedDataStorageProxy INSTANCE = ASMProxyFactory.create(SavedDataStorageProxy.class);

    @FieldGetter(name = "cache")
    Map<Object, Optional<?>> getCache(Object target);

    @FieldSetter(name = "cache")
    void setCache(Object target, Map<Object, Optional<?>> cache);

    @MethodInvoker(name = "readTagFromDisk", activeIf = "max_version=1.21.11")
    default CompoundTag readTagFromDisk$0(Object target, String name, DataFixTypes type, int version) throws IOException {
        throw new UnsupportedOperationException();
    }

    @MethodInvoker(name = "getDataFile", activeIf = "min_version=26.1")
    default Path getDataFile(Object target, @Type(clazz = IdentifierProxy.class) Object id) {
        throw new UnsupportedOperationException();
    }

    @MethodInvoker(name = "readTagFromDisk", activeIf = "min_version=26.1")
    default CompoundTag readTagFromDisk$1(Object target, Path path, DataFixTypes type, int version) throws IOException {
        throw new UnsupportedOperationException();
    }
}
