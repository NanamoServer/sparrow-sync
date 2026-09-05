package net.momirealms.sparrow.sync.proxy.minecraft.world.entity.ai.attributes;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldSetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Consumer;

@ReflectionProxy(name = "net.minecraft.world.entity.ai.attributes.AttributeInstance", activeIf = "min_version=1.21.4")
public interface AttributeInstanceProxy {
    AttributeInstanceProxy INSTANCE = ASMProxyFactory.create(AttributeInstanceProxy.class);

    @FieldGetter(name = "modifierById")
    Map<Object, AttributeModifier> getModifierById(Object target);

    @Nullable
    @FieldGetter(name = "onDirty", optional = true)
    default Consumer<AttributeInstance> getOnDirty(Object target) {
        return null;
    }

    @FieldSetter(name = "onDirty", optional = true)
    default void setOnDirty(Object target, Consumer<AttributeInstance> callback) {
        throw new UnsupportedOperationException("AttributeInstance.onDirty setter is unavailable");
    }
}
