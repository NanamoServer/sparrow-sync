package net.momirealms.sparrow.sync.proxy.minecraft.nbt;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.TagParser", activeIf = "min_version=1.21.5")
public interface TagParserProxy1_21_5 {
    TagParserProxy1_21_5 INSTANCE = ASMProxyFactory.create(TagParserProxy1_21_5.class);

    @MethodInvoker(name = "create", isStatic = true)
    Object create(DynamicOps<?> ops);

    @MethodInvoker(name = "parseFully")
    Object parseFully(Object target, String input) throws CommandSyntaxException;
}
