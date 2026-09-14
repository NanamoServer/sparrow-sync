package net.momirealms.sparrow.sync.snapshot.data;

import com.mojang.serialization.Codec;
import net.momirealms.sparrow.nbt.CompoundTag;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.nbt.codec.NBTOps;
import net.momirealms.sparrow.sync.snapshot.data.type.ExperienceDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.GameModeDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HealthScaleDataType;
import net.momirealms.sparrow.sync.snapshot.data.type.HungerDataType;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataTypeCodecTest {

    @Test
    void experienceCodecRoundTrips() {
        ExperienceDataType.Experience value = new ExperienceDataType.Experience(1024, 30, 0.45f);
        assertEquals(value, roundTrip(ExperienceDataType.Experience.CODEC, value));
    }

    @Test
    void healthCodecRoundTrips() {
        HealthDataType.Health value = new HealthDataType.Health(19.5);
        assertEquals(value, roundTrip(HealthDataType.Health.CODEC, value));
    }

    @Test
    void healthScaleCodecRoundTripsWithoutNativeCapability() {
        HealthScaleDataType.HealthScale value = new HealthScaleDataType.HealthScale(40.0, true);
        assertEquals(value, roundTrip(HealthScaleDataType.HealthScale.CODEC, value));
        assertFalse(NativePlayerDataType.class.isAssignableFrom(HealthScaleDataType.class));
    }

    @Test
    void hungerCodecRoundTrips() {
        HungerDataType.Hunger value = new HungerDataType.Hunger(18, 5.0f, 0.4f, 37);
        assertEquals(value, roundTrip(HungerDataType.Hunger.CODEC, value));
    }

    @Test
    void hungerCodecDefaultsLegacyTickTimerToZero() {
        CompoundTag legacy = NBT.createCompound();
        legacy.putInt("food", 18);
        legacy.putFloat("saturation", 5.0f);
        legacy.putFloat("exhaustion", 0.4f);

        assertEquals(new HungerDataType.Hunger(18, 5.0f, 0.4f, 0), HungerDataType.Hunger.CODEC.parse(NBTOps.INSTANCE, legacy).getOrThrow());
    }

    // 药水效果改走 NMS MobEffectInstance CODEC (保留隐藏效果链), 依赖注册表, 由 3.8 真机手测覆盖

    @Test
    void gameModeDecodesKnownNameAndRejectsUnknown() throws IOException {
        GameModeDataType type = new GameModeDataType();
        CompoundTag ignored = NBT.createCompound();

        GameMode[] modes = GameMode.values();
        for (int i = 0; i < modes.length; i++) {
            GameMode mode = modes[i];
            assertEquals(mode, type.decode(NBT.createString(mode.name())));
            assertEquals(mode.name(), type.encode(mode).getAsString());
            assertEquals(mode, type.decode(type.encode(mode)));
        }
        assertThrows(IOException.class, () -> type.decode(NBT.createString("survival")));
        assertThrows(IOException.class, () -> type.decode(NBT.createString("NOT_A_MODE")));
        assertThrows(IOException.class, () -> type.decode(ignored));
    }

    private static <T> T roundTrip(Codec<T> codec, T value) {
        return codec.parse(NBTOps.INSTANCE, codec.encodeStart(NBTOps.INSTANCE, value).getOrThrow()).getOrThrow();
    }
}
