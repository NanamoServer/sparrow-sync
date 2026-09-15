package net.momirealms.sparrow.sync.test;

import io.lettuce.core.RedisURI;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import org.junit.jupiter.api.Assumptions;

public final class RedisTestSupport {
    private RedisTestSupport() {
    }

    public static PluginConfig.RedisOptions options(int database) {
        String url = System.getProperty("sparrow.test.redis");
        Assumptions.assumeTrue(url != null, "Set sparrow.test.redis to a disposable Redis instance for integration tests");
        RedisURI uri = RedisURI.create(url);
        uri.setDatabase(database);
        PluginConfig.RedisOptions options = new PluginConfig.RedisOptions();
        NmsPlayerFixture.set(PluginConfig.RedisOptions.class, options, "url", uri.toString());
        return options;
    }
}
