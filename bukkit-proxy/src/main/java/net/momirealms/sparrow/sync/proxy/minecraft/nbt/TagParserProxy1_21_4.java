package net.momirealms.sparrow.sync.proxy.minecraft.nbt;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.ConstructorInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.nbt.TagParser", activeIf = "version=1.21.4")
public interface TagParserProxy1_21_4 {
    TagParserProxy1_21_4 INSTANCE = ASMProxyFactory.create(TagParserProxy1_21_4.class);

    @ConstructorInvoker
    Object newInstance(StringReader reader);

    @MethodInvoker(name = "readValue")
    Object readValue(Object target) throws CommandSyntaxException;
}
