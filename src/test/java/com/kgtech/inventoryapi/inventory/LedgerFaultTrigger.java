package com.kgtech.inventoryapi.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test fixture that raises real Postgres errors from a trigger on inventory_ledger, so the driver and Spring's
 * exception translation run for real. Test-only DDL (plan OQ3). Sequences are non-transactional, so the attempt
 * counter and the failed attempt's txid survive the rollback of that attempt. Creates no tables.
 */
final class LedgerFaultTrigger {

    private final JdbcTemplate jdbc;

    LedgerFaultTrigger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Raises {@code sqlState} on the first {@code failures} ledger inserts for {@code skuId}, at statement time. */
    void failOnInsert(String skuId, long failures, String sqlState) {
        createSequencesAndFaultFunction();
        jdbc.execute("CREATE TRIGGER test_fault BEFORE INSERT ON inventory_ledger "
                + "FOR EACH ROW EXECUTE FUNCTION test_fault(" + args(skuId, failures, sqlState) + ")");
    }

    /** Raises {@code sqlState} on the first {@code failures} ledger inserts for {@code skuId}, at commit time. */
    void failOnCommit(String skuId, long failures, String sqlState) {
        createSequencesAndFaultFunction();
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_fault AFTER INSERT ON inventory_ledger "
                + "DEFERRABLE INITIALLY DEFERRED "
                + "FOR EACH ROW EXECUTE FUNCTION test_fault(" + args(skuId, failures, sqlState) + ")");
    }

    /** Raises P0001 on any ledger insert for {@code skuId} made outside a SERIALIZABLE transaction. */
    void failUnlessSerializable(String skuId) {
        createSequences();
        jdbc.execute("""
                CREATE FUNCTION test_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.sku_id = TG_ARGV[0] THEN
                    PERFORM nextval('test_fault_attempts');
                    IF current_setting('transaction_isolation') <> 'serializable' THEN
                      RAISE EXCEPTION 'not serializable: %', current_setting('transaction_isolation')
                        USING ERRCODE = 'P0001';
                    END IF;
                  END IF;
                  RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER test_fault BEFORE INSERT ON inventory_ledger "
                + "FOR EACH ROW EXECUTE FUNCTION test_fault('" + skuId + "')");
    }

    /** Number of ledger inserts for the SKU that reached the trigger, failed or not. */
    long attempts() {
        Long value = jdbc.queryForObject(
                "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM test_fault_attempts", Long.class);
        return value == null ? 0 : value;
    }

    /** txid of the most recent attempt the trigger failed. */
    long failedTxid() {
        Long value = jdbc.queryForObject("SELECT last_value FROM test_fault_txid", Long.class);
        return value == null ? 0 : value;
    }

    void drop() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fault ON inventory_ledger");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fault()");
        jdbc.execute("DROP SEQUENCE IF EXISTS test_fault_attempts, test_fault_txid");
    }

    private void createSequences() {
        jdbc.execute("CREATE SEQUENCE test_fault_attempts");
        jdbc.execute("CREATE SEQUENCE test_fault_txid");
    }

    private void createSequencesAndFaultFunction() {
        createSequences();
        jdbc.execute("""
                CREATE FUNCTION test_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.sku_id = TG_ARGV[0] THEN
                    IF nextval('test_fault_attempts') <= TG_ARGV[1]::bigint THEN
                      PERFORM setval('test_fault_txid', txid_current());
                      RAISE EXCEPTION 'forced fault' USING ERRCODE = TG_ARGV[2];
                    END IF;
                  END IF;
                  RETURN NEW;
                END $$
                """);
    }

    private static String args(String skuId, long failures, String sqlState) {
        return "'" + skuId + "', '" + failures + "', '" + sqlState + "'";
    }
}
