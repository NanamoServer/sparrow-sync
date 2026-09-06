package net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.Map;

@ReflectionProxy(name = "net.minecraft.world.level.saveddata.maps.MapItemSavedData")
public interface MapItemSavedDataProxy {
    MapItemSavedDataProxy INSTANCE = ASMProxyFactory.create(MapItemSavedDataProxy.class);

    @FieldGetter(name = "CODEC", isStatic = true, activeIf = "min_version=1.21.5")
    default Codec<MapItemSavedData> getCodec() {
        return null;
    }

    @MethodInvoker(name = "save", activeIf = "max_version=1.21.4")
    default CompoundTag save(Object target, CompoundTag tag, HolderLookup.Provider registries) {
        throw new UnsupportedOperationException();
    }

    @MethodInvoker(name = "load", isStatic = true, activeIf = "max_version=1.21.4")
    default MapItemSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        throw new UnsupportedOperationException();
    }

    @FieldGetter(name = "bannerMarkers")
    Map<String, MapBanner> getBannerMarkers(Object target);

    @FieldSetter(name = "trackedDecorationCount")
    void setTrackedDecorationCount(Object target, int count);

    @MethodInvoker(name = "setColorsDirty")
    void setColorsDirty(Object target, int x, int y);

    @MethodInvoker(name = "setDecorationsDirty")
    void setDecorationsDirty(Object target);
}
