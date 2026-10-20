package example.shardpilot

/** Explicit JDBC transaction boundaries, with separate tables so feature order is irrelevant. */
// Tables exist only in a runtime Testcontainers database, not an IDE-managed datasource.
@SuppressWarnings(['SqlNoDataSourceInspection', 'SqlResolve'])
class PostgresTransactionSpec extends AbstractInfrastructureSpec {
    def 'rolls back uncommitted rows'() {
        when:
        def count = database().withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                // DDL is committed first so rollback removes only the inserted row.
                statement.execute('CREATE TABLE rolled_back (id integer PRIMARY KEY)')
                connection.setAutoCommit(false)
                statement.executeUpdate('INSERT INTO rolled_back VALUES (1)')
                connection.rollback()
                statement.executeQuery('SELECT count(*) FROM rolled_back').withCloseable { rows ->
                    rows.next() ? rows.getInt(1) : null
                }
            }
        }

        then:
        count == 0
    }

    def 'exposes committed rows to another connection'() {
        when:
        database().withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute('CREATE TABLE committed (id integer PRIMARY KEY)')
                connection.setAutoCommit(false)
                statement.executeUpdate('INSERT INTO committed VALUES (1)')
                connection.commit()
            }
        }
        // Observe after the writer closes; do not accidentally assert only its local transaction state.
        def count = database().withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery('SELECT count(*) FROM committed').withCloseable { rows ->
                    rows.next() ? rows.getInt(1) : null
                }
            }
        }

        then:
        count == 1
    }
}
