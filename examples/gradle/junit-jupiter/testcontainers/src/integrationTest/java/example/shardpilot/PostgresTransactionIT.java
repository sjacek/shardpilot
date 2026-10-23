package example.shardpilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit JDBC transaction boundaries inside this class's disposable database. */
class PostgresTransactionIT extends AbstractInfrastructureIT {
    @Test
    void rollsBackUncommittedRows() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE rolled_back (id integer PRIMARY KEY)");
            // Commit table creation first so rollback removes only the row, not the assertion's table.
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO rolled_back VALUES (1)");
            connection.rollback();
            try (var rows = statement.executeQuery("SELECT count(*) FROM rolled_back")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test
    void exposesCommittedRowsToAnotherConnection() throws Exception {
        try (var connection = database(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE committed (id integer PRIMARY KEY)");
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO committed VALUES (1)");
            connection.commit();
        }
        // A fresh connection proves persistence beyond the writer's transaction and connection lifetime.
        try (var observer = database(); var statement = observer.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM committed")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }
}
