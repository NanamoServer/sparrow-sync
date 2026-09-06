package net.momirealms.sparrow.sync.map;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapDataTest {
    static CompoundTag content(int pixel) {
        CompoundTag tag = NBT.createCompound();
        tag.putString("dimension", "minecraft:overworld");
        tag.putInt("xCenter", 64);
        tag.putInt("zCenter", -128);
        tag.putByte("scale", (byte) 2);
        byte[] colors = new byte[MapData.PIXEL_COUNT];
        colors[0] = (byte) pixel;
        tag.putByteArray("colors", colors);
        return tag;
    }

    @Test
    void ownsPixelsAndRemovesServerLocalState() throws Exception {
        CompoundTag tag = content(7);
        tag.put("frames", NBT.createList());
        tag.putLong("UUIDMost", 1);
        tag.putLong("UUIDLeast", 2);
        MapData data = new MapData(4440, tag);
        tag.getByteArray("colors")[0] = 9;
        data.getTag().getByteArray("colors")[0] = 10;
        CompoundTag decoded = NBT.fromBytes(data.encode());
        assertNotNull(decoded);
        assertEquals(7, decoded.getByteArray("colors")[0]);
        assertNull(decoded.get("frames"));
        assertNull(decoded.get("UUIDMost"));
        assertEquals(data, new MapData(4440, decoded));
    }

    @Test
    void validatesPayloadAndIdentityWithoutAliasingSourceIds() {
        assertThrows(IllegalArgumentException.class, () -> new MapData(0, content(1)));
        CompoundTag invalid = content(1);
        invalid.putByteArray("colors", new byte[1]);
        assertThrows(IllegalArgumentException.class, () -> new MapData(4440, invalid));
        assertThrows(IllegalArgumentException.class, () -> new MapSource("owner", -1));
        assertThrows(IllegalArgumentException.class, () -> new MapIdentity(new MapSource("owner", 0), 0));
        MapIdentity first = new MapIdentity(new MapSource("Owner/世界", 0), -1);
        MapIdentity second = new MapIdentity(new MapSource("Owner/世界", 1), -1);
        assertTrue(first.replicaDimension().matches("[a-z0-9_.-]+:[a-z0-9/._-]+"));
        assertNotEquals(first.replicaDimension(), second.replicaDimension());
        assertNotEquals(first.replicaDimension(), new MapIdentity(first.source(), -2).replicaDimension());
        assertEquals(first, MapIdentity.fromReplicaDimension(first.replicaDimension()));
        assertNull(MapIdentity.fromReplicaDimension("minecraft:overworld"));
        assertNull(MapIdentity.fromReplicaDimension("sparrow-sync:map/not-hex/0/-1"));
        assertNull(MapIdentity.fromReplicaDimension("sparrow-sync:map/41/0/1"));
        assertNull(MapIdentity.fromReplicaDimension(first.replicaDimension() + "/extra"));
    }
}
