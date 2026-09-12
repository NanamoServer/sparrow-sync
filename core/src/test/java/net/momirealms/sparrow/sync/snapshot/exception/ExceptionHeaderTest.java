package net.momirealms.sparrow.sync.snapshot.exception;

import net.momirealms.sparrow.sync.snapshot.codec.SnapshotFixtures;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实文件验证本地头的三态摘要, 类型顺序与损坏边界. */
class ExceptionHeaderTest {
    @TempDir Path directory; // 每轮测试独立的头文件目录

    /**
     * 无摘要, 有效空摘要与非空摘要分别往返, 字节布局独立于快照格式.
     *
     * @throws IOException 当测试头文件读写失败时
     */
    @Test
    void summaryStatesAndEntryOrderSurviveRoundTrip() throws IOException {
        Path body = this.directory.resolve("entry.snapshot");
        Map<DataKey, Integer> summary = new LinkedHashMap<>();
        summary.put(DataKey.of("test", "z"), 0);
        summary.put(DataKey.of("test", "a"), -1);
        summary.put(DataKey.of("test", "m"), 123);
        for (ExceptionHeader header : new ExceptionHeader[]{
                new ExceptionHeader(null, null), new ExceptionHeader(null, null, Map.of()),
                new ExceptionHeader(SnapshotFixtures.meta(), "Steve", summary)}) {
            header.write(body);
            assertEquals(1, Files.readAllBytes(ExceptionHeader.path(body))[0]);
            ExceptionHeader restored = ExceptionHeader.read(body);
            assertEquals(header, restored);
            if (header.summary() != null) {
                assertEquals(new ArrayList<>(header.summary().keySet()), new ArrayList<>(restored.summary().keySet()));
                assertThrows(UnsupportedOperationException.class, () -> restored.summary().put(DataKey.of("test", "extra"), 1));
            }
        }
        ExceptionHeader copy = new ExceptionHeader(null, null, summary);
        summary.clear();
        assertEquals(3, copy.summary().size());
        assertFalse(Files.exists(body));
    }

    /**
     * 首字节决定本地头版本, 开发期的 int 魔数头直接落入版本不受支持的分支.
     *
     * @param version 不支持的首字节
     * @throws IOException 当测试文件写入失败时
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 2, 83, 255})
    void unsupportedVersionsAreRejected(int version) throws IOException {
        Path body = this.directory.resolve("entry.snapshot");
        Files.write(ExceptionHeader.path(body), new byte[]{(byte) version, 0x53, 0x48, 1});
        assertThrows(IOException.class, () -> ExceptionHeader.read(body));
    }

    /**
     * -1 是唯一合法的负数量, 巨大数量由 EOF 中止, 容器按已读条目增长.
     *
     * @param count 文件声明的损坏数量
     * @throws IOException 当测试文件写入失败时
     */
    @ParameterizedTest
    @ValueSource(ints = {-2, Integer.MIN_VALUE, 1, Integer.MAX_VALUE})
    void invalidCountsAndTruncatedListsAreRejected(int count) throws IOException {
        Path body = this.directory.resolve("entry.snapshot");
        Files.write(ExceptionHeader.path(body), ByteBuffer.allocate(7).put((byte) 1).put((byte) 0).put((byte) 0).putInt(count).array());
        IOException failure = assertThrows(IOException.class, () -> ExceptionHeader.read(body));
        if (count > 0) {
            assertInstanceOf(EOFException.class, failure);
        }
    }

    /**
     * 条目缺少体量, 体量小于 -1 或类型名重复时拒绝整份摘要.
     *
     * @param damage 本次破坏的条目字段
     * @throws IOException 当测试文件写入失败时
     */
    @ParameterizedTest
    @ValueSource(strings = {"truncated", "negative", "duplicate"})
    void malformedEntriesAreRejected(String damage) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            output.writeBoolean(false);
            output.writeBoolean(false);
            output.writeInt(damage.equals("duplicate") ? 2 : 1);
            output.writeUTF("test:data");
            if (!damage.equals("truncated")) {
                output.writeInt(damage.equals("negative") ? -2 : 0);
            }
            if (damage.equals("duplicate")) {
                output.writeUTF("test:data");
                output.writeInt(1);
            }
        }
        Path body = this.directory.resolve("entry.snapshot");
        Files.write(ExceptionHeader.path(body), bytes.toByteArray());
        assertThrows(IOException.class, () -> ExceptionHeader.read(body));
    }

    /**
     * 三种摘要状态都以文件末尾为边界, 多出的字节不能被忽略.
     *
     * @throws IOException 当测试文件写入失败时
     */
    @Test
    void everySummaryStateRejectsTrailingBytes() throws IOException {
        Path body = this.directory.resolve("entry.snapshot");
        for (ExceptionHeader header : new ExceptionHeader[]{new ExceptionHeader(null, null),
                new ExceptionHeader(null, null, Map.of()), new ExceptionHeader(null, null, Map.of(DataKey.of("test", "data"), -1))}) {
            header.write(body);
            Files.write(ExceptionHeader.path(body), new byte[]{1}, StandardOpenOption.APPEND);
            assertThrows(IOException.class, () -> ExceptionHeader.read(body));
        }
    }
}
