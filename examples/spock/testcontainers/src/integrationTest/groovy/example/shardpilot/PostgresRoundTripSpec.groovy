package example.shardpilot

/** Data-table iterations share one specification owner and database, but use distinct row identities. */
// Tables exist only in a runtime Testcontainers database, not an IDE-managed datasource.
@SuppressWarnings(['SqlNoDataSourceInspection', 'SqlResolve'])
class PostgresRoundTripSpec extends AbstractInfrastructureSpec {
    def 'stores and reads entry #code with amount #amount'() {
        when:
        def entries = database().withCloseable { connection ->
            // The second iteration reuses the schema, not a connection or an earlier row identity.
            connection.createStatement().withCloseable { statement ->
                statement.execute('CREATE TABLE IF NOT EXISTS entries (code text PRIMARY KEY, amount numeric(12,2) NOT NULL)')
            }
            connection.prepareStatement('INSERT INTO entries VALUES (?, ?)').withCloseable { insert ->
                insert.setString(1, code)
                insert.setBigDecimal(2, amount)
                insert.executeUpdate()
            }
            connection.prepareStatement('SELECT code, amount FROM entries WHERE code = ?').withCloseable { query ->
                query.setString(1, code)
                query.executeQuery().withCloseable { rows ->
                    def found = []
                    while (rows.next()) found.add([code: rows.getString('code'), amount: rows.getBigDecimal('amount')])
                    found
                }
            }
        }

        then:
        entries == [[code: code, amount: amount]]

        where:
        code           | amount
        'demo-entry-a' | 12.50G
        'demo-entry-b' | 7.25G
    }

    def 'aggregates amounts independently of the round trip feature'() {
        when:
        def total = database().withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute('CREATE TABLE amounts (amount numeric(12,2) NOT NULL)')
                statement.execute('INSERT INTO amounts VALUES (10.00), (2.50)')
                statement.executeQuery('SELECT sum(amount) FROM amounts').withCloseable { rows ->
                    rows.next() ? rows.getBigDecimal(1) : null
                }
            }
        }

        then:
        total == 12.50G
    }
}
