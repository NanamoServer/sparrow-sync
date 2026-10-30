package net.momirealms.sparrow.sync.proxy.minecraft.world.item.component;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.item.component.ResolvableProfile;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;
import org.jetbrains.annotations.NotNull;

@ReflectionProxy(name = "net.minecraft.world.item.component.ResolvableProfile")
public interface ResolvableProfileProxy {
    ResolvableProfileProxy INSTANCE = ASMProxyFactory.create(ResolvableProfileProxy.class);

    @NotNull
    @MethodInvoker(name = "createResolved", isStatic = true, activeIf = "min_version=1.21.9")
    default ResolvableProfile createResolved(@NotNull GameProfile profile) {
        return this.newInstance(profile);
    }

    @NotNull
    @ConstructorInvoker(activeIf = "max_version=1.21.8")
    default ResolvableProfile newInstance(@NotNull GameProfile profile) {
        throw new UnsupportedOperationException();
    }
}