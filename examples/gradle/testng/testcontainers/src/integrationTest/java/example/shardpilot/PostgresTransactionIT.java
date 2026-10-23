package example.shardpilot;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** JDBC transaction boundaries, with separate tables so test-method order is irrelevant. */
// Tables are created at runtime inside Testcontainers, not in an IDE-managed datasource.
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class PostgresTransactionIT extends AbstractInfrastructureIT {
    /**
     * Verifies rollback against real database state, leaving schema creation outside the rolled-back work.
     *
     * @throws Exception when fixture access or JDBC operations fail
     */
    @Test
    public void rollsBackUncommittedRows() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE rolled_back (id integer PRIMARY KEY)");
            // DDL commits before the transaction, so rollback removes the row but preserves its table.
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO rolled_back VALUES (1)");
            connection.rollback();
            try (var rows = statement.executeQuery("SELECT count(*) FROM rolled_back")) {
                assertTrue(rows.next());
                assertEquals(rows.getInt(1), 0);
            }
        }
    }

    /**
     * Verifies commit visibility beyond the writing connection's lifetime, not just within its own session.
     *
     * @throws Exception when fixture access or JDBC operations fail
     */
    @Test
    public void exposesCommittedRowsToAnotherConnection() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE committed (id integer PRIMARY KEY)");
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO committed VALUES (1)");
            connection.commit();
        }
        // Observe from a new connection rather than only checking the writer's transaction state.
        try (var observer = database(); var statement = observer.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM committed")) {
            assertTrue(rows.next());
            assertEquals(rows.getInt(1), 1);
        }
    }
}
