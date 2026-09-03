package net.momirealms.sparrow.sync.proxy.minecraft.advancements;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

@ReflectionProxy(name = "net.minecraft.advancements.CriterionProgress", activeIf = "min_version=1.21.4")
public interface CriterionProgressProxy {
    CriterionProgressProxy INSTANCE = ASMProxyFactory.create(CriterionProgressProxy.class);

    @FieldSetter(name = "obtained")
    void setObtained(Object target, @Nullable Instant obtained);
}
