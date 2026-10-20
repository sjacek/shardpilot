package example.shardpilot;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** JDBC examples that also show why nested tests must stay with their outer class's shard and fixture. */
class PostgresRoundTripIT extends AbstractInfrastructureIT {
    @Test
    void storesAndReadsAnEntry() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE entries (code text PRIMARY KEY, amount numeric(12,2) NOT NULL)");
            // Bind values independently of SQL; fixture database identifiers are handled by infrastructure setup.
            try (var insert = connection.prepareStatement("INSERT INTO entries VALUES (?, ?)")) {
                insert.setString(1, "demo-entry");
                insert.setBigDecimal(2, new BigDecimal("12.50"));
                assertEquals(1, insert.executeUpdate());
            }
            try (var rows = statement.executeQuery("SELECT code, amount FROM entries")) {
                assertTrue(rows.next());
                assertEquals("demo-entry", rows.getString("code"));
                assertEquals(new BigDecimal("12.50"), rows.getBigDecimal("amount"));
                assertFalse(rows.next());
            }
        }
    }

    /** The TSV's nested weight belongs to the outer family; it does not create a separate database or shard. */
    @Nested
    class Totals {
        @Test
        void aggregatesAmountsInTheSameClassFamily() throws Exception {
            // A separate table keeps this nested test independent of its outer test's execution order.
            try (var connection = database(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE amounts (amount numeric(12,2) NOT NULL)");
                statement.execute("INSERT INTO amounts VALUES (10.00), (2.50)");
                try (var rows = statement.executeQuery("SELECT sum(amount) FROM amounts")) {
                    assertTrue(rows.next());
                    assertEquals(new BigDecimal("12.50"), rows.getBigDecimal(1));
                }
            }
        }
    }
}
