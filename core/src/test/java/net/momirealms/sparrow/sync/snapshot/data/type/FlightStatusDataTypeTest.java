package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import net.momirealms.sparrow.sync.snapshot.data.CaptureMode;
import net.momirealms.sparrow.sync.test.NmsPlayerFixture;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightStatusDataTypeTest {
    @Test
    void capturesDecodesAndAppliesFlightStatus() throws IOException {
        Player player = player();
        FlightStatusDataType type = new FlightStatusDataType();
        player.setAllowFlight(true);
        player.setFlying(true);

        CraftPlayer source = NmsPlayerFixture.create();
        source.getHandle().getAbilities().mayfly = true;
        source.getHandle().getAbilities().flying = true;
        FlightStatusDataType.FlightStatus captured = type.decode(type.encode(type.capture(source, CaptureMode.SYNC)), 0);

        assertEquals(new FlightStatusDataType.FlightStatus(true, true), captured);

        player.setAllowFlight(false);
        type.apply(player, new FlightStatusDataType.FlightStatus(true, true));

        assertTrue(player.getAllowFlight());
        assertTrue(player.isFlying());
    }

    @Test
    void clearsFlyingWhenFlightIsNotAllowed() {
        Player player = player();
        FlightStatusDataType type = new FlightStatusDataType();
        player.setAllowFlight(true);
        player.setFlying(true);

        type.apply(player, new FlightStatusDataType.FlightStatus(false, true));

        assertFalse(player.getAllowFlight());
        assertFalse(player.isFlying());
    }

    @Test
    void declaresGameModeDependencyAndRemainsNonCritical() {
        FlightStatusDataType type = new FlightStatusDataType();

        assertEquals(StorageFormat.STRUCTURED, type.storage());
        assertEquals(Set.of(GameModeDataType.GAME_MODE), type.dependencies());
        assertFalse(type.critical());
    }

    private static Player player() {
        boolean[] state = new boolean[2];
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getAllowFlight" -> state[0];
            case "isFlying" -> state[1];
            case "setAllowFlight" -> {
                state[0] = (boolean) args[0];
                if (!state[0]) {
                    state[1] = false;
                }
                yield null;
            }
            case "setFlying" -> {
                boolean flying = (boolean) args[0];
                if (flying && !state[0]) {
                    throw new IllegalArgumentException("flight is not allowed");
                }
                state[1] = flying;
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
}
