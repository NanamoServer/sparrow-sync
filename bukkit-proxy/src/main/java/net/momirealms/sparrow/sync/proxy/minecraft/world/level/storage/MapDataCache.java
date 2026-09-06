package net.momirealms.sparrow.sync.proxy.minecraft.world.level.storage;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.momirealms.sparrow.sync.proxy.minecraft.world.level.saveddata.maps.MapItemSavedDataProxy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 原生地图入库前替换 Codec 遍历的集合, 保留地图、MapView 和像素数组的身份. */
public final class MapDataCache extends ConcurrentHashMap<Object, Optional<?>> {

    public MapDataCache(@NotNull Map<Object, Optional<?>> original) {
        super(original.size());
        // 启动时已加载的地图与后续原生 get/set 写入走同一个准备入口.
        original.forEach(this::put);
    }

    @Override
    @Nullable
    public Optional<?> put(@NotNull Object key, @NotNull Optional<?> value) {
        if (value.orElse(null) instanceof MapItemSavedData data) {
            MapItemSavedDataProxy proxy = MapItemSavedDataProxy.INSTANCE;
            if (!(proxy.getBannerMarkers(data) instanceof ConcurrentMap)) {
                proxy.setBannerMarkers(data, new ConcurrentHashMap<>(proxy.getBannerMarkers(data)));
            }
            if (!(proxy.getFrameMarkers(data) instanceof ConcurrentMap)) {
                proxy.setFrameMarkers(data, new ConcurrentHashMap<>(proxy.getFrameMarkers(data)));
            }
        }
        // 发布引用前完成集合替换. 异步侧只读缓存, 不参与原生加载或创建地图.
        return super.put(key, value);
    }
}
