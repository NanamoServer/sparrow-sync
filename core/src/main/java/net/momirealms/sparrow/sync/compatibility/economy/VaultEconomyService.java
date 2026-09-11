package net.momirealms.sparrow.sync.compatibility.economy;

import net.milkbowl.vault.economy.Economy;
import net.momirealms.sparrow.sync.locale.LogConstants;
import net.momirealms.sparrow.sync.locale.TranslationManager;
import net.momirealms.sparrow.sync.plugin.SparrowSync;
import net.momirealms.sparrow.sync.snapshot.data.DataRegistry;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VaultEconomyService {
    private final SparrowSync plugin;
    private final ServicesManager services;
    private final DataRegistry dataRegistry;
    private Economy economy;

    public VaultEconomyService(@NotNull SparrowSync plugin,
                               @NotNull ServicesManager services,
                               @NotNull DataRegistry dataRegistry) {
        this.plugin = plugin;
        this.services = services;
        this.dataRegistry = dataRegistry;
    }

    public void onDelayedEnable() {
        Economy provider = this.registeredProvider();
        if (provider == null || !provider.isEnabled()) {
            throw new IllegalStateException("Vault is installed but no economy provider is registered");
        }
        this.economy = provider;
        this.plugin.logger().info(TranslationManager.console(LogConstants.PLUGIN_ECONOMY_READY, provider.getName()));
        this.dataRegistry.register(new EmoneyDataType(this));
    }

    @NotNull
    public Economy provider() {
        return this.economy;
    }

    @Nullable
    private Economy registeredProvider() {
        try {
            RegisteredServiceProvider<Economy> registration = this.services.getRegistration(Economy.class);
            return registration == null ? null : registration.getProvider();
        } catch (Throwable throwable) {
            return null;
        }
    }
}
