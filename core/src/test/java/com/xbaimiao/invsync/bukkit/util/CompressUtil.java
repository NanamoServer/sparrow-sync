package com.xbaimiao.invsync.bukkit.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public final class CompressUtil {
    public static final CompressUtil INSTANCE = new CompressUtil();

    public String ungzipString(byte[] bytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
