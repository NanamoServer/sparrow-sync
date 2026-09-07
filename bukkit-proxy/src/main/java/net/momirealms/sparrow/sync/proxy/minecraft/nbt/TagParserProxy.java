package net.momirealms.sparrow.sync.proxy.minecraft.nbt;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.TagParser", activeIf = "min_version=1.21.4")
public interface TagParserProxy {
    TagParserProxy INSTANCE = ASMProxyFactory.create(TagParserProxy.class);

    @ConstructorInvoker(activeIf = "version=1.21.4")
    Object newInstance(StringReader reader);

    @MethodInvoker(name = "readValue", activeIf = "version=1.21.4")
    Object readValue(Object target) throws CommandSyntaxException;

    @MethodInvoker(name = "create", isStatic = true, activeIf = "min_version=1.21.5")
    Object create(DynamicOps<?> ops);

    @MethodInvoker(name = "parseFully", activeIf = "min_version=1.21.5")
    Object parseFully(Object target, String input) throws CommandSyntaxException;
}
