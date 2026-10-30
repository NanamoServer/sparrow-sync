package net.momirealms.sparrow.sync.plugin.dependency.transform;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class LettuceTransformer implements DependencyTransformer {
    private static final String WATCHDOG = "io/lettuce/core/protocol/MaintenanceAwareConnectionWatchdog";
    private static final String ATTRIBUTE_KEY = "io/netty/util/AttributeKey";
    private static final String ATTRIBUTE_FACTORY = "(Ljava/lang/String;)Lio/netty/util/AttributeKey;";
    private static final String REBIND_KEY = "net.momirealms.sparrow.sync.lettuce.rebindAddress";

    @NotNull
    @Override
    public String cacheKey() {
        return "lettuce-rebind-v1";
    }

    @NotNull
    @Override
    public byte[] transform(@NotNull String internalClassName, @NotNull byte[] bytecode) {
        if (!WATCHDOG.equals(internalClassName)) return bytecode;
        return RebindAttributeVisitor.transform(bytecode);
    }

    // Dependencies 注册时 ASM 尚未下载, 在执行变换时才加载访问器.
    private static final class RebindAttributeVisitor extends ClassVisitor {
        private int keyReplacements;
        private int factoryReplacements;

        private RebindAttributeVisitor(ClassWriter writer) {
            super(Opcodes.ASM9, writer);
        }

        private static byte[] transform(byte[] bytecode) {
            ClassReader reader = new ClassReader(bytecode);
            ClassWriter writer = new ClassWriter(0);
            RebindAttributeVisitor visitor = new RebindAttributeVisitor(writer);
            reader.accept(visitor, 0);
            if (visitor.keyReplacements != 1 || visitor.factoryReplacements != 1) {
                throw new IllegalStateException("Lettuce rebind attribute initializer changed; review the dependency transformer");
            }
            return writer.toByteArray();
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!"<clinit>".equals(name)) return delegate;
            return new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitLdcInsn(Object value) {
                    // 多份 Lettuce 共享服务端 Netty, 属性名使用 SparrowSync 自己的命名空间.
                    if ("rebindAddress".equals(value)) {
                        RebindAttributeVisitor.this.keyReplacements++;
                        super.visitLdcInsn(REBIND_KEY);
                    } else {
                        super.visitLdcInsn(value);
                    }
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && ATTRIBUTE_KEY.equals(owner) && "newInstance".equals(name) && ATTRIBUTE_FACTORY.equals(descriptor)) {
                        RebindAttributeVisitor.this.factoryReplacements++;
                        super.visitMethodInsn(opcode, owner, "valueOf", descriptor, isInterface);
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                }
            };
        }
    }
}