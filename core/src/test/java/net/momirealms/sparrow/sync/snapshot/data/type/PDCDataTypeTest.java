package net.momirealms.sparrow.sync.snapshot.data.type;

import net.minecraft.nbt.StringTag;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.PDCMergeBlacklist;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDCDataTypeTest {
    private static final PDCMergeBlacklist BLACKLIST = PDCMergeBlacklist.of(List.of(
            "sparrow-sync-ignore",
            List.of("sparrow-sync", "ignore")
    ));
    private static final Method ENCODE_COMPOUND = declaredMethod("encodeCompound", Iterable.class, PDCMergeBlacklist.class);
    private static final Method MERGE_COMPOUND = declaredMethod("mergeCompound", net.minecraft.nbt.CompoundTag.class, net.minecraft.nbt.CompoundTag.class, PDCMergeBlacklist.class);

    @Test
    void captureRemovesBlacklistedPathsWithoutMutatingRawData() {
        Map<String, net.minecraft.nbt.Tag> raw = new LinkedHashMap<>();
        raw.put("notignore", StringTag.valueOf("value"));
        raw.put("sparrow-sync-ignore", StringTag.valueOf("somevalue"));
        net.minecraft.nbt.CompoundTag ignored = new net.minecraft.nbt.CompoundTag();
        ignored.put("key", StringTag.valueOf("value"));
        net.minecraft.nbt.CompoundTag sync = new net.minecraft.nbt.CompoundTag();
        sync.put("ignore", ignored);
        sync.put("notIgnore", StringTag.valueOf("value"));
        raw.put("sparrow-sync", sync);

        CompoundTag snapshot = encodeCompound(raw, BLACKLIST);
        CompoundTag expected = NBT.createCompound();
        expected.putString("notignore", "value");
        CompoundTag expectedSync = NBT.createCompound();
        expectedSync.putString("notIgnore", "value");
        expected.put("sparrow-sync", expectedSync);

        assertEquals(expected, snapshot);
        assertTrue(raw.containsKey("sparrow-sync-ignore"));
        assertTrue(sync.get("ignore") instanceof net.minecraft.nbt.CompoundTag);
    }

    @Test
    void segmentedPathDoesNotMatchColonInsideOneKey() {
        Map<String, net.minecraft.nbt.Tag> raw = new LinkedHashMap<>();
        raw.put("sparrow-sync:ignore", StringTag.valueOf("flat"));
        net.minecraft.nbt.CompoundTag sync = new net.minecraft.nbt.CompoundTag();
        sync.put("ignore", StringTag.valueOf("nested"));
        raw.put("sparrow-sync", sync);
        PDCMergeBlacklist blacklist = PDCMergeBlacklist.of(List.of(List.of("sparrow-sync", "ignore")));

        CompoundTag snapshot = encodeCompound(raw, blacklist);

        assertEquals("flat", snapshot.getString("sparrow-sync:ignore"));
        assertTrue(snapshot.getCompound("sparrow-sync").isEmpty());
    }

    @Test
    void applyRecursivelyMergesWhilePreservingBlacklistedPaths() {
        net.minecraft.nbt.CompoundTag raw = new net.minecraft.nbt.CompoundTag();
        raw.put("sparrow-sync-ignore", StringTag.valueOf("local-root"));
        net.minecraft.nbt.CompoundTag localIgnored = new net.minecraft.nbt.CompoundTag();
        localIgnored.put("key", StringTag.valueOf("local-key"));
        net.minecraft.nbt.CompoundTag localSync = new net.minecraft.nbt.CompoundTag();
        localSync.put("ignore", localIgnored);
        localSync.put("notIgnore", StringTag.valueOf("old"));
        localSync.put("localOnly", StringTag.valueOf("local-only"));
        raw.put("sparrow-sync", localSync);

        net.minecraft.nbt.CompoundTag snapshot = new net.minecraft.nbt.CompoundTag();
        snapshot.putString("sparrow-sync-ignore", "remote-root");
        net.minecraft.nbt.CompoundTag remoteIgnored = new net.minecraft.nbt.CompoundTag();
        remoteIgnored.putString("key", "remote-key");
        net.minecraft.nbt.CompoundTag remoteSync = new net.minecraft.nbt.CompoundTag();
        remoteSync.put("ignore", remoteIgnored);
        remoteSync.putString("notIgnore", "new");
        remoteSync.putString("remoteOnly", "remote-only");
        snapshot.put("sparrow-sync", remoteSync);

        mergeCompound(raw, snapshot, BLACKLIST);

        assertEquals("local-root", stringValue(raw.get("sparrow-sync-ignore")));
        net.minecraft.nbt.CompoundTag mergedSync = assertInstanceOf(net.minecraft.nbt.CompoundTag.class, raw.get("sparrow-sync"));
        net.minecraft.nbt.CompoundTag mergedIgnored = assertInstanceOf(net.minecraft.nbt.CompoundTag.class, mergedSync.get("ignore"));
        assertEquals("local-key", stringValue(mergedIgnored.get("key")));
        assertEquals("new", stringValue(mergedSync.get("notIgnore")));
        assertEquals("local-only", stringValue(mergedSync.get("localOnly")));
        assertEquals("remote-only", stringValue(mergedSync.get("remoteOnly")));
    }

    @Test
    void applyDoesNotRestoreMissingBlacklistedPathsFromOldSnapshot() {
        net.minecraft.nbt.CompoundTag raw = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag snapshot = new net.minecraft.nbt.CompoundTag();
        snapshot.putString("sparrow-sync-ignore", "remote-root");
        net.minecraft.nbt.CompoundTag remoteSync = new net.minecraft.nbt.CompoundTag();
        remoteSync.putString("ignore", "remote-ignore");
        remoteSync.putString("notIgnore", "remote-value");
        snapshot.put("sparrow-sync", remoteSync);

        mergeCompound(raw, snapshot, BLACKLIST);

        assertNull(raw.get("sparrow-sync-ignore"));
        net.minecraft.nbt.CompoundTag mergedSync = assertInstanceOf(net.minecraft.nbt.CompoundTag.class, raw.get("sparrow-sync"));
        assertNull(mergedSync.get("ignore"));
        assertEquals("remote-value", stringValue(mergedSync.get("notIgnore")));
    }

    @Test
    void applyKeepsLocalCompoundWhenRemoteTypeWouldEraseIgnoredDescendant() {
        net.minecraft.nbt.CompoundTag raw = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag localSync = new net.minecraft.nbt.CompoundTag();
        localSync.put("ignore", StringTag.valueOf("local-ignore"));
        localSync.put("notIgnore", StringTag.valueOf("local-value"));
        raw.put("sparrow-sync", localSync);
        net.minecraft.nbt.CompoundTag snapshot = new net.minecraft.nbt.CompoundTag();
        snapshot.putString("sparrow-sync", "remote-scalar");

        mergeCompound(raw, snapshot, BLACKLIST);

        net.minecraft.nbt.CompoundTag preserved = assertInstanceOf(net.minecraft.nbt.CompoundTag.class, raw.get("sparrow-sync"));
        assertEquals("local-ignore", stringValue(preserved.get("ignore")));
        assertEquals("local-value", stringValue(preserved.get("notIgnore")));
    }

    private static String stringValue(net.minecraft.nbt.Tag tag) {
        return assertInstanceOf(StringTag.class, tag).value();
    }

    private static CompoundTag encodeCompound(Map<String, net.minecraft.nbt.Tag> raw, PDCMergeBlacklist blacklist) {
        try {
            return (CompoundTag) ENCODE_COMPOUND.invoke(null, raw.entrySet(), blacklist);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void mergeCompound(net.minecraft.nbt.CompoundTag raw, net.minecraft.nbt.CompoundTag snapshot, PDCMergeBlacklist blacklist) {
        try {
            MERGE_COMPOUND.invoke(null, raw, snapshot, blacklist);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Method declaredMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = PDCDataType.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
