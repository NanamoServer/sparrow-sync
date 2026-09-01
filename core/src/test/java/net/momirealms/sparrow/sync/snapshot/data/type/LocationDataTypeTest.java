package net.momirealms.sparrow.sync.snapshot.data.type;

import net.momirealms.sparrow.sync.snapshot.StorageFormat;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationDataTypeTest {
    @Test
    void capturesDecodesAndAppliesLocation() throws IOException {
        World sourceWorld = world("source");
        World targetWorld = world("target");
        Location[] location = {new Location(sourceWorld, 12.5, 64.25, -3.75, 91.5f, -18.25f)};
        Player player = player(location, server(Map.of("source", sourceWorld, "target", targetWorld)));
        LocationDataType type = new LocationDataType();

        LocationDataType.PlayerLocation captured = type.decode(type.capture(player), 0);

        assertEquals(new LocationDataType.PlayerLocation("source", 12.5, 64.25, -3.75, 91.5f, -18.25f), captured);

        type.apply(player, new LocationDataType.PlayerLocation("target", -8.0, 72.0, 14.5, 45.0f, 12.0f));

        Location applied = player.getLocation();
        assertSame(targetWorld, applied.getWorld());
        assertEquals(-8.0, applied.getX());
        assertEquals(72.0, applied.getY());
        assertEquals(14.5, applied.getZ());
        assertEquals(45.0f, applied.getYaw());
        assertEquals(12.0f, applied.getPitch());
    }

    @Test
    void declaresDependenciesAndRemainsNonCritical() {
        LocationDataType type = new LocationDataType();

        assertEquals(StorageFormat.STRUCTURED, type.storage());
        assertEquals(Set.of(FlightStatusDataType.FLIGHT_STATUS, PotionEffectsDataType.POTION_EFFECTS), type.dependencies());
        assertFalse(type.critical());
    }

    @Test
    void rejectsMissingTargetWorldWithItsName() {
        World currentWorld = world("current");
        Location[] location = {new Location(currentWorld, 0.0, 64.0, 0.0)};
        Player player = player(location, server(Map.of("current", currentWorld)));
        LocationDataType type = new LocationDataType();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> type.apply(player, new LocationDataType.PlayerLocation("missing_world", 0.0, 64.0, 0.0, 0.0f, 0.0f)));

        assertTrue(exception.getMessage().contains("missing_world"));
    }

    @Test
    void rejectsCancelledTeleport() {
        World world = world("target");
        Location[] location = {new Location(world, 0.0, 64.0, 0.0)};
        Player player = player(location, server(Map.of("target", world)), false);
        LocationDataType type = new LocationDataType();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> type.apply(player, new LocationDataType.PlayerLocation("target", 10.0, 80.0, 10.0, 0.0f, 0.0f)));

        assertTrue(exception.getMessage().contains("target"));
        assertEquals(0.0, location[0].getX());
    }

    private static Player player(Location[] location, Server server) {
        return player(location, server, true);
    }

    private static Player player(Location[] location, Server server, boolean teleportResult) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getLocation" -> location[0].clone();
            case "getServer" -> server;
            case "teleport" -> {
                if (teleportResult) {
                    location[0] = ((Location) args[0]).clone();
                }
                yield teleportResult;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static Server server(Map<String, World> worlds) {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            if (method.getName().equals("getWorld")) return worlds.get((String) args[0]);
            throw new UnsupportedOperationException(method.getName());
        });
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            if (method.getName().equals("getName")) return name;
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
