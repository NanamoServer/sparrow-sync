package net.momirealms.sparrow.sync.proxy.minecraft.stats;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

import java.util.Set;

@ReflectionProxy(name = "net.minecraft.stats.ServerRecipeBook", activeIf = "min_version=1.21.4")
public interface ServerRecipeBookProxy {
    ServerRecipeBookProxy INSTANCE = ASMProxyFactory.create(ServerRecipeBookProxy.class);

    @FieldGetter(name = "known")
    Set<ResourceKey<Recipe<?>>> getKnown(Object target);

    @FieldGetter(name = "highlight")
    Set<ResourceKey<Recipe<?>>> getHighlight(Object target);
}
