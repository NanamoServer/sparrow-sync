package net.momirealms.sparrow.sync.plugin.dependency.transform;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class TransformationHandler {

    private TransformationHandler() {}

    public static void transform(@NotNull Path input, @NotNull Path output, @NotNull DependencyTransformer transformer) throws IOException {
        Path temporary = Files.createTempFile(output.getParent(), "transform-", ".jar");
        try {
            try (JarFile source = new JarFile(input.toFile()); JarOutputStream target = new JarOutputStream(Files.newOutputStream(temporary))) {
                Enumeration<JarEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    target.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream stream = source.getInputStream(entry)) {
                        if (entry.getName().endsWith(".class")) {
                            String className = entry.getName().substring(0, entry.getName().length() - 6);
                            target.write(transformer.transform(className, stream.readAllBytes()));
                        } else {
                            stream.transferTo(target);
                        }
                    }
                    target.closeEntry();
                }
            }
            // 补丁全部成功后才发布缓存, 变换失败时保留原始下载文件.
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}