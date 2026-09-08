package net.momirealms.sparrow.sync.storage;

import net.momirealms.sparrow.sync.snapshot.SaveCause;
import net.momirealms.sparrow.sync.util.UUIDUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotRowMapperTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mapsBothJdbcUuidRepresentationsWithoutReadingPayloadForMetadata(boolean nativeUuid) throws Exception {
        UUID id = UUID.fromString("fedcba98-7654-3210-ffff-000102030405");
        UUID player = UUID.randomUUID();
        byte[] data = {1, 2, 3};
        Map<String, Object> row = Map.of(
                "id", nativeUuid ? id : UUIDUtils.toBytes(id),
                "player", nativeUuid ? player : UUIDUtils.toBytes(player),
                "ts", 123L, "cause", "COMMAND", "pinned", true, "server", "server", "mc_data", 4440,
                "format", 2, "data", data);
        List<String> reads = new ArrayList<>();
        ResultSet result = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
            String column = (String) arguments[0];
            reads.add(column);
            return row.get(column);
        });
        var meta = SnapshotRowMapper.readMeta(result);
        assertEquals(id, meta.id());
        assertEquals(player, meta.player());
        assertEquals(123L, meta.timestamp());
        assertEquals(SaveCause.COMMAND, meta.cause());
        assertTrue(meta.pinned());
        assertFalse(reads.contains("format"));
        assertFalse(reads.contains("data"));
        SnapshotRow mapped = new SnapshotRowMapper().map(result, null);
        assertEquals(meta, mapped.meta());
        assertEquals(2, mapped.format());
        assertSame(data, mapped.data());
    }
}
