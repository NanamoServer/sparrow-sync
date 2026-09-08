package net.momirealms.sparrow.sync.storage.postgresql;

import net.momirealms.sparrow.sync.storage.StorageProvider.SaveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class PostgresFailureClassifierTest {
    @ParameterizedTest
    @ValueSource(strings = {"08006", "40001", "40P01", "55P03", "57014", "57P01", "53300", "53100", "28P01", "42501", "3D000", "3F000"})
    void retainsFailuresThatCanSucceedAfterRecovery(String state) {
        assertEquals(SaveResult.RETRY_LATER, PostgresFailureClassifier.classify(new SQLException("failure", state)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"22001", "22021", "23502", "23505", "23514"})
    void rejectsInvalidStoredData(String state) {
        assertEquals(SaveResult.REJECTED_MALFORMED, PostgresFailureClassifier.classify(new SQLException("failure", state)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"42601", "42P01", "42703", "07001", "0A000"})
    void keepsProgrammingErrorsExceptional(String state) {
        assertNull(PostgresFailureClassifier.classify(new SQLException("failure", state)));
    }

    @Test
    void detectsProgramLimitAndPreservesNestedSqlCause() {
        SQLException sql = new SQLException("limit", "54000");
        assertEquals(SaveResult.REJECTED_OVERSIZED, PostgresFailureClassifier.classify(sql));
        assertSame(sql, PostgresFailureClassifier.sqlCause(new RuntimeException(sql)));
        assertNull(PostgresFailureClassifier.sqlCause(new RuntimeException()));
    }
}
