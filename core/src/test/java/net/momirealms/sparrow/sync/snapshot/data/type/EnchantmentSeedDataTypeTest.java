package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EnchantmentSeedDataTypeTest {
    @Test
    void capturesDecodesAndAppliesEnchantmentSeed() throws IOException {
        int[] seed = new int[1];
        Player player = player(seed);
        EnchantmentSeedDataType type = new EnchantmentSeedDataType();
        player.setEnchantmentSeed(135792468);

        CraftPlayer source = NmsPlayerFixture.create();
        source.getHandle().enchantmentSeed = 135792468;
        int captured = type.decode(type.encode(type.capture(source, CaptureMode.SYNC)), 0);

        assertEquals(135792468, captured);

        type.apply(player, -246813579);

        assertEquals(-246813579, player.getEnchantmentSeed());
    }

    @Test
    void declaresStructuredNonCriticalData() {
        EnchantmentSeedDataType type = new EnchantmentSeedDataType();
        assertFalse(type.critical());
    }

    private static Player player(int[] seed) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getEnchantmentSeed" -> seed[0];
            case "setEnchantmentSeed" -> {
                seed[0] = (int) args[0];
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
