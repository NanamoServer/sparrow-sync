package net.momirealms.sparrow.sync.proxy.minecraft.server;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import net.momirealms.sparrow.reflection.proxy.annotation.Type;

import java.util.Map;
import java.util.Set;

@ReflectionProxy(name = "net.minecraft.server.PlayerAdvancements", activeIf = "min_version=1.21.4")
public interface PlayerAdvancementsProxy {
    PlayerAdvancementsProxy INSTANCE = ASMProxyFactory.create(PlayerAdvancementsProxy.class);

    @FieldGetter(name = "progress")
    Map<Object, Object> getProgress(Object target);

    @FieldGetter(name = "progressChanged")
    Set<Object> getProgressChanged(Object target);

    @MethodInvoker(name = "stopListening", activeIf = "max_version=26.1.2")
    void stopListening(Object target);

    @MethodInvoker(name = "clearTriggers", activeIf = "min_version=26.2")
    void clearTriggers(Object target);

    @MethodInvoker(name = "markForVisibilityUpdate")
    void markForVisibilityUpdate(Object target, @Type(name = "net.minecraft.advancements.AdvancementHolder") Object advancement);

    @MethodInvoker(name = "registerListeners")
    void registerListeners(Object target, @Type(name = "net.minecraft.server.ServerAdvancementManager") Object manager);

    @MethodInvoker(name = "flushDirty", activeIf = "version=1.21.4")
    void flushDirty(Object target, @Type(name = "net.minecraft.server.level.ServerPlayer") Object player);

    @MethodInvoker(name = "flushDirty", activeIf = "min_version=1.21.5")
    void flushDirty$0(Object target, @Type(name = "net.minecraft.server.level.ServerPlayer") Object player, boolean showAdvancements);
}
