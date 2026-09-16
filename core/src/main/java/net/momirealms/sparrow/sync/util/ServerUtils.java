package net.momirealms.sparrow.sync.util;

import net.momirealms.sparrow.sync.proxy.minecraft.server.MinecraftServerProxy;

public final class ServerUtils {
    private ServerUtils() {}

    public static boolean isStopping() {
        return MinecraftServerProxy.INSTANCE.hasStopped(MinecraftServerProxy.INSTANCE.getServer());
    }

    public static boolean isRunning() {
        return MinecraftServerProxy.INSTANCE.isRunning(MinecraftServerProxy.INSTANCE.getServer());
    }
}
