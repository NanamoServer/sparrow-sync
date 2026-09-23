package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.session.PlayerSession;
import net.momirealms.sparrow.sync.session.SessionManager;
import net.momirealms.sparrow.sync.snapshot.data.NativePlayerDataType.NativeApplyResult;
import net.momirealms.sparrow.sync.test.ConnectionFixture;
import net.momirealms.sparrow.sync.test.PluginConfigExtension;
import net.momirealms.sparrow.sync.util.VersionHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(PluginConfigExtension.class)
class PurpurBarDataTypeTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void roundTripsAndOverwritesAllNativeToggles(int bits) throws Exception {
        PurpurBarDataType type = new PurpurBarDataType();
        PurpurBarDataType.PurpurBars value = new PurpurBarDataType.PurpurBars((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
        assertEquals(value, type.decode(type.encode(value)));

        CompoundTag playerData = NBT.createCompound();
        playerData.putBoolean("Purpur.TPSBar", !value.tpsBar());
        playerData.putBoolean("Purpur.CompassBar", !value.compassBar());
        playerData.putBoolean("Purpur.RamBar", !value.ramBar());
        playerData.putString("unrelated", "kept");
        PlayerSession session = new SessionManager(null).tryOpen(new UUID(0, 0), "Steve", ConnectionFixture.create());

        assertEquals(NativeApplyResult.APPLIED_PLAYER_DATA, type.applyNative(session, playerData, value));
        assertEquals(value.tpsBar(), playerData.getBoolean("Purpur.TPSBar"));
        assertEquals(value.compassBar(), playerData.getBoolean("Purpur.CompassBar"));
        assertEquals(value.ramBar(), playerData.getBoolean("Purpur.RamBar"));
        assertEquals("kept", playerData.getString("unrelated"));
    }

    @Test
    void nativeApplyFollowsPlayerDataOption() throws Exception {
        PurpurBarDataType type = new PurpurBarDataType();
        PlayerSession session = new SessionManager(null).tryOpen(new UUID(0, 0), "Steve", ConnectionFixture.create());
        assertTrue(type.shouldApply(session));
        Object options = PluginConfig.synchronization$nativeAsyncApply();
        Field field = options.getClass().getDeclaredField("playerData");
        field.setAccessible(true);
        field.setBoolean(options, false);
        assertFalse(type.shouldApply(session));
    }

    @Test
    void paperDoesNotReportPurpurEvenWithOptionEnabled() {
        assertTrue(PluginConfig.synchronization$dataTypes().purpurBar());
        assertFalse(VersionHelper.isPurpur());
    }
}
