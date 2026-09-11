package net.momirealms.sparrow.sync.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlServerVersionTest {
    private static final MysqlServerVersion MINIMUM = new MysqlServerVersion(8, 4, 0);

    @Test
    void parsesTheLeadingThreeSegmentsAndIgnoresTheBuildSuffix() {
        assertEquals(new MysqlServerVersion(8, 4, 0), parsed("8.4.0"));
        assertEquals(new MysqlServerVersion(9, 7, 0), parsed("9.7.0"));
        assertEquals(new MysqlServerVersion(26, 7, 0), parsed("26.7.0"));
        assertEquals(new MysqlServerVersion(8, 0, 36), parsed("8.0.36-0ubuntu0.22.04.1"));
        assertEquals(new MysqlServerVersion(8, 4, 2), parsed("8.4.2-log"));
    }

    @Test
    void rejectsTextThatDoesNotOpenWithThreeNumericSegments() {
        assertNull(MysqlServerVersion.parse(""));
        assertNull(MysqlServerVersion.parse("MariaDB"));
        assertNull(MysqlServerVersion.parse("v8.4.0"));
        assertNull(MysqlServerVersion.parse("8"));
        assertNull(MysqlServerVersion.parse("8.4"));
        assertNull(MysqlServerVersion.parse("8..4"));
        assertNull(MysqlServerVersion.parse("8.4.x"));
        assertNull(MysqlServerVersion.parse("99999999999.4.0")); // 单段数字超出 int 范围
    }

    @Test
    void acceptsTheMinimumAndEverythingAboveIt() {
        assertTrue(parsed("8.4.0").atLeast(MINIMUM));
        assertTrue(parsed("8.4.1").atLeast(MINIMUM));
        assertTrue(parsed("8.10.0").atLeast(MINIMUM));
        assertTrue(parsed("9.7.0").atLeast(MINIMUM));
        assertTrue(parsed("26.7.0").atLeast(MINIMUM));
        // MariaDB 等兼容实现同样按三段数字比较, 版本号本身够高就放行
        assertTrue(parsed("11.4.2-MariaDB-1:11.4.2+maria~ubu2204").atLeast(MINIMUM));
    }

    @Test
    void rejectsEverythingBelowTheMinimum() {
        assertFalse(parsed("8.3.99").atLeast(MINIMUM)); // 高位段决定结果, 低位段再高也不补
        assertFalse(parsed("8.0.36").atLeast(MINIMUM));
        assertFalse(parsed("5.7.44").atLeast(MINIMUM));
        assertFalse(parsed("7.99.99").atLeast(MINIMUM));
    }

    @Test
    void printsTheThreeSegmentsInDottedForm() {
        assertEquals("8.4.0", MINIMUM.toString());
        assertEquals("26.7.0", parsed("26.7.0").toString());
    }

    private static MysqlServerVersion parsed(String version) {
        MysqlServerVersion parsed = MysqlServerVersion.parse(version);
        assertNotNull(parsed, version);
        return parsed;
    }
}
