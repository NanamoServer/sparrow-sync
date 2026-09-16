package net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps;

import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.maps.MapBanner;
import net.minecraft.world.level.saveddata.maps.MapFrame;
import net.minecraft.world.level.saveddata.maps.MapId;
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

    @FieldSetter(name = "bannerMarkers")
    void setBannerMarkers(Object target, Map<String, MapBanner> markers);

    @FieldGetter(name = "frameMarkers")
    Map<String, MapFrame> getFrameMarkers(Object target);

    @FieldSetter(name = "frameMarkers")
    void setFrameMarkers(Object target, Map<String, MapFrame> markers);

    @MethodInvoker(name = "type", isStatic = true, activeIf = "min_version=1.21.5")
    default Object type(MapId id) {
        throw new UnsupportedOperationException();
    }

    @FieldSetter(name = "trackedDecorationCount")
    void setTrackedDecorationCount(Object target, int count);

    @MethodInvoker(name = "setColorsDirty")
    void setColorsDirty(Object target, int x, int y);

    @MethodInvoker(name = "setDecorationsDirty")
    void setDecorationsDirty(Object target);
}
