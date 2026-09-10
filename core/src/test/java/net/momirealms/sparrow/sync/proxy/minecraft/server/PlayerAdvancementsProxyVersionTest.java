package net.momirealms.sparrow.sync.proxy.minecraft.server;

import net.momirealms.sparrow.sync.proxy.BukkitProxy;
import net.momirealms.sparrow.sync.proxy.minecraft.advancements.CriterionListenerProxy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerAdvancementsProxyVersionTest {

    @Test
    void legacyListenerTokenCanBeRecreatedForRemoval() {
        BukkitProxy.init("1.21.8", List.of("paper"));

        Object first = CriterionListenerProxy.INSTANCE.newInstance(null, null, "criterion");
        Object second = CriterionListenerProxy.INSTANCE.newInstance(null, null, "criterion");

        assertEquals(first, second);
    }
}
