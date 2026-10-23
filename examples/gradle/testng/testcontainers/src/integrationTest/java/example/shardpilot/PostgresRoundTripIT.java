package example.shardpilot;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Data-provider rows share their class's database and shard, but use independent row identities. */
// Tables are created at runtime inside Testcontainers, not in an IDE-managed datasource.
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class PostgresRoundTripIT extends AbstractInfrastructureIT {
    /**
     * Supplies independent row identities to two invocations of one test method.
     * Both invocations still belong to this class's shard and database. Provider failures are test failures,
     * and provider parallelism is disabled separately from Gradle worker/TestNG suite parallelism.
     *
     * @return pairs of a unique entry code and an exact decimal amount
     */
    @DataProvider(name = "entries", parallel = false, propagateFailureAsTestFailure = true)
    public static Object[][] entries() {
        return new Object[][] {
                {"demo-entry-a", new BigDecimal("12.50")},
                {"demo-entry-b", new BigDecimal("7.25")}
        };
    }

    /**
     * Reads back only this invocation's inserted row; it does not depend on another provider row's state.
     *
     * @param code the provider's unique business identifier, bound as a value rather than SQL text
     * @param amount the exact decimal value expected after PostgreSQL's numeric round-trip
     * @throws Exception when fixture access or JDBC operations fail
     */
    @Test(dataProvider = "entries")
    public void storesAndReadsEntry(String code, BigDecimal amount) throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            // Both rows may initialize this table; neither row relies on the other row's execution order.
            statement.execute("CREATE TABLE IF NOT EXISTS entries (code text PRIMARY KEY, amount numeric(12,2) NOT NULL)");
            try (var insert = connection.prepareStatement("INSERT INTO entries VALUES (?, ?)")) {
                insert.setString(1, code);
                insert.setBigDecimal(2, amount);
                assertEquals(insert.executeUpdate(), 1);
            }
            try (var query = connection.prepareStatement("SELECT code, amount FROM entries WHERE code = ?")) {
                query.setString(1, code);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(rows.getString("code"), code);
                    assertEquals(rows.getBigDecimal("amount"), amount);
                    assertFalse(rows.next());
                }
            }
        }
    }

    /**
     * Exercises aggregation in a separate table, without depending on the parameterized method running first.
     *
     * @throws Exception when fixture access or JDBC operations fail
     */
    @Test
    public void aggregatesAmountsIndependentlyOfTheRoundTrip() throws Exception {
        // A separate table keeps this method independent of the data provider's schema and rows.
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE amounts (amount numeric(12,2) NOT NULL)");
            statement.execute("INSERT INTO amounts VALUES (10.00), (2.50)");
            try (var rows = statement.executeQuery("SELECT sum(amount) FROM amounts")) {
                assertTrue(rows.next());
                assertEquals(rows.getBigDecimal(1), new BigDecimal("12.50"));
            }
        }
    }
}
