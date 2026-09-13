package net.momirealms.sparrow.sync.storage.mysql;

import com.zaxxer.hikari.HikariDataSource;
import net.momirealms.sparrow.nbt.NBT;
import net.momirealms.sparrow.sync.map.data.MapData;
import net.momirealms.sparrow.sync.map.data.MapSource;
import net.momirealms.sparrow.sync.player.PlayerSerialExecutor;
import net.momirealms.sparrow.sync.plugin.configuration.PluginConfig;
import net.momirealms.sparrow.sync.plugin.logger.SyncLogger;
import net.momirealms.sparrow.sync.snapshot.codec.SnapshotDataCodec;
import net.momirealms.sparrow.sync.snapshot.codec.compressor.CompressorRegistry;
import net.momirealms.sparrow.sync.snapshot.data.DataKey;
import net.momirealms.sparrow.sync.snapshot.model.SaveCause;
import net.momirealms.sparrow.sync.snapshot.model.Snapshot;
import net.momirealms.sparrow.sync.snapshot.model.SnapshotMeta;
import net.momirealms.sparrow.sync.storage.SnapshotQuery;
import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import net.momirealms.sparrow.sync.storage.mariadb.MariaDbStorageProvider;
import net.momirealms.sparrow.sync.storage.StoredUser;
import net.momirealms.sparrow.sync.test.SnapshotFileTestLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.MariaDbDataSource;
import org.mariadb.jdbc.export.MaxAllowedPacketException;

import java.lang.reflect.Field;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SPARROW_TEST_MARIADB_URL", matches = ".+")
class MariaDbStorageProviderTest {
    @Test
    void roundTripsWithMysqlDriverExcluded() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) urls.add(new File(entry).toURI().toURL());
        for (ClassLoader loader = this.getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlLoader) urls.addAll(Arrays.asList(urlLoader.getURLs()));
        }
        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.mysql.")) throw new ClassNotFoundException("MySQL driver excluded: " + name);
                return super.loadClass(name, resolve);
            }
        }) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(isolated);
            try {
                isolated.loadClass(Probe.class.getName()).getMethod("run").invoke(null);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    public static final class Probe {
        public static void run() throws Exception {
            Field config = PluginConfig.class.getDeclaredField("config");
            config.setAccessible(true);
            config.set(null, new PluginConfig.ConfigDefinition());
            String prefix = "maria_it_" + UUID.randomUUID().toString().replace("-", "") + "_";
            PluginConfig.MariaDbOptions options = new PluginConfig.MariaDbOptions();
            for (Map.Entry<String, String> entry : Map.of(
                    "url", System.getenv("SPARROW_TEST_MARIADB_URL"),
                    "username", System.getenv("SPARROW_TEST_MARIADB_USERNAME"),
                    "password", System.getenv("SPARROW_TEST_MARIADB_PASSWORD"),
                    "tablePrefix", prefix
            ).entrySet()) {
                Field field = PluginConfig.MysqlOptions.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(options, entry.getValue());
            }
            SyncLogger logger = new SyncLogger(new SnapshotFileTestLogger());
            PlayerSerialExecutor serial = new PlayerSerialExecutor(logger, 1);
            MysqlStorageProvider provider = new MariaDbStorageProvider(options, new SnapshotDataCodec(CompressorRegistry.DEFLATE), serial, Runnable::run, logger);
            MariaDbDataSource cleanup = new MariaDbDataSource(options.url());
            cleanup.setUser(options.username());
            cleanup.setPassword(options.password());
            try {
                provider.initialize();
                System.out.println("MariaDB integration server: " + provider.jdbi().withHandle(handle -> handle.createQuery("SELECT VERSION()").mapTo(String.class).one()));
                assertEquals(Configuration.parse(options.url()).database(), provider.jdbi().withHandle(handle -> handle.getConnection().getCatalog()));
                assertTrue(provider.jdbi().withHandle(handle -> handle.getConnection().getMetaData().getDriverName()).contains("MariaDB"));
                assertEquals(4, provider.jdbi().withHandle(handle -> handle.createQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND LEFT(table_name, :length) = :prefix")
                        .bind("length", prefix.length()).bind("prefix", prefix).mapTo(Integer.class).one()).intValue());
                Field poolField = MysqlStorageProvider.class.getDeclaredField("dataSource");
                poolField.setAccessible(true);
                HikariDataSource pool = (HikariDataSource) poolField.get(provider);
                assertEquals("sparrow-sync-mariadb", pool.getPoolName());

                UUID player = UUID.randomUUID();
                Snapshot first = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 100, SaveCause.COMMAND, false, "大厅-É😀", 4440), Map.of(DataKey.of("external", "data"), NBT.createLongArray(new long[]{Long.MIN_VALUE, 42})));
                Snapshot second = new Snapshot(new SnapshotMeta(UUID.randomUUID(), player, 200, SaveCause.COMMAND, false, "大厅-É😀", 4440), first.content());
                assertEquals(SaveResult.SAVED, provider.saveSnapshot(first).join());
                assertEquals(SaveResult.SAVED, provider.importSnapshot(second).join().result());
                assertEquals(first, provider.snapshot(first.meta().id()).join().orElseThrow());
                assertEquals(second, provider.latestSnapshot(player).join().orElseThrow());
                assertEquals(List.of(second.meta(), first.meta()), provider.listSnapshots(player).join());
                assertEquals(2L, provider.countSnapshots(SnapshotQuery.of(player)).join());
                assertEquals(List.of(first.meta()), provider.listSnapshots(SnapshotQuery.of(player).withOffset(1).withLimit(1)).join());
                assertEquals(1, provider.rotate(player, 1).join());
                assertTrue(provider.snapshot(first.meta().id()).join().isEmpty());
                StoredUser user = new StoredUser(player, "测试玩家", 1234);
                provider.importUser(user).join();
                assertEquals(player, provider.lookupUser(user.name()).join().orElseThrow());
                assertEquals(List.of(user), provider.scanUsers(null, 10).join());

                var tag = NBT.createCompound();
                tag.putString("dimension", "minecraft:overworld");
                tag.putByteArray("colors", new byte[MapData.PIXEL_COUNT]);
                MapData data = new MapData(4440, tag);
                var stored = provider.maps().register(new MapSource("maria-test", 1), data).join();
                assertEquals(-1, stored.identity().globalId());
                assertEquals(stored, provider.maps().register(stored.identity().source(), data).join());
                provider.maps().update(stored.identity(), data).join();
                assertEquals(stored, provider.maps().find(-1).join().orElseThrow());
                assertEquals(1L, provider.maps().sequence().join());
                var archive = provider.maps().scan(0, 10).join().getFirst();
                provider.maps().importMap(archive).join();
                assertEquals(stored, provider.maps().find(-1).join().orElseThrow());

                assertEquals(SaveResult.RETRY_LATER, MysqlFailureClassifier.classify(new SQLException("deadlock", "40001", 1213)));
                assertEquals(SaveResult.REJECTED_OVERSIZED, MysqlFailureClassifier.classify(new SQLException("packet too large", "08000", new MaxAllowedPacketException("oversized", true))));
                provider.shutdown();
                assertTrue(pool.isClosed());
                provider.initialize();
                assertEquals(second, provider.latestSnapshot(player).join().orElseThrow());
                assertEquals(stored, provider.maps().find(-1).join().orElseThrow());
            } finally {
                serial.shutdown(5, TimeUnit.SECONDS);
                provider.shutdown();
                try (var connection = cleanup.getConnection(); var statement = connection.createStatement()) {
                    for (String table : List.of("snapshots", "users", "maps", "meta")) statement.execute("DROP TABLE IF EXISTS `" + prefix + table + "`");
                    try (var remaining = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND LEFT(table_name, " + prefix.length() + ") = '" + prefix + "'")) {
                        assertTrue(remaining.next());
                        assertEquals(0, remaining.getInt(1));
                    }
                }
                var drivers = DriverManager.getDrivers();
                while (drivers.hasMoreElements()) {
                    var driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == Probe.class.getClassLoader()) DriverManager.deregisterDriver(driver);
                }
            }
        }
    }
}
