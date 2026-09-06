package net.momirealms.sparrow.sync.plugin.configuration;

import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig.PDCMergeBlacklist;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PDCMergeConfigTest {
    @Test
    void scalarAndSegmentedPathsRemainDistinct() {
        PDCMergeBlacklist blacklist = PDCMergeBlacklist.of(List.of(
                "sparrow-sync:ignore",
                List.of("sparrow-sync", "ignore")
        ));

        assertTrue(blacklist.child("sparrow-sync:ignore").terminal());
        assertTrue(blacklist.child("sparrow-sync").child("ignore").terminal());
    }

}
