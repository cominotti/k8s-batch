// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC client for verifying MySQL data via port-forwarded connection.
 */
public final class MysqlVerifier {

    private static final Logger log = LoggerFactory.getLogger(MysqlVerifier.class);

    private static final String CUSTOMERS_TABLE = "customers";
    private static final String ACCOUNTS_TABLE = "accounts";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * Creates a JDBC verifier that connects to MySQL via a port-forwarded connection. Opens a new
     * connection per method call (no pooling) — acceptable overhead for E2E test verification where
     * simplicity trumps connection reuse.
     *
     * @param localPort the locally-forwarded MySQL port
     * @param database  the MySQL database name
     * @param username  the MySQL username
     * @param password  the MySQL password
     */
    public MysqlVerifier(int localPort, String database, String username, String password) {
        this.jdbcUrl = "jdbc:mysql://localhost:" + localPort + "/" + database
                // allowPublicKeyRetrieval=true is required for MySQL 8 when not using SSL —
                // the connector needs the RSA public key for password authentication
                + "?useSSL=false&allowPublicKeyRetrieval=true";
        this.username = username;
        this.password = password;
        log.info("MysqlVerifier configured | jdbcUrl={}", jdbcUrl);
    }

    /**
     * Counts rows in target_records table.
     */
    public int countTargetRecords() throws SQLException {
        return countRows("target_records");
    }

    /**
     * Counts step executions matching a name pattern within a specific job execution.
     *
     * <p>Always scopes by JOB_EXECUTION_ID to avoid counting step executions from other test
     * runs — multiple test methods launch the same job, and step executions accumulate across
     * runs in the shared MySQL instance.
     *
     * @param jobExecutionId the job execution ID to scope by
     * @param stepNamePattern SQL LIKE pattern for step names (e.g., "worker:%")
     * @return the number of matching step executions
     * @throws SQLException on database errors
     */
    public int countStepExecutionsForJob(long jobExecutionId, String stepNamePattern) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ? AND STEP_NAME LIKE ?")) {
            ps.setLong(1, jobExecutionId);
            ps.setString(2, stepNamePattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Queries step executions for a job execution.
     *
     * @param jobExecutionId the job execution ID to query
     * @return a list of maps, each containing keys STEP_NAME ({@code String}), STATUS
     *         ({@code String}), EXIT_CODE ({@code String}), READ_COUNT ({@code int}),
     *         WRITE_COUNT ({@code int}), COMMIT_COUNT ({@code int})
     * @throws SQLException on database errors
     */
    public List<Map<String, Object>> queryStepExecutions(long jobExecutionId) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT STEP_NAME, STATUS, EXIT_CODE, READ_COUNT, WRITE_COUNT, COMMIT_COUNT " +
                     "FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = ?")) {
            ps.setLong(1, jobExecutionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("STEP_NAME", rs.getString("STEP_NAME"));
                    row.put("STATUS", rs.getString("STATUS"));
                    row.put("EXIT_CODE", rs.getString("EXIT_CODE"));
                    row.put("READ_COUNT", rs.getInt("READ_COUNT"));
                    row.put("WRITE_COUNT", rs.getInt("WRITE_COUNT"));
                    row.put("COMMIT_COUNT", rs.getInt("COMMIT_COUNT"));
                    results.add(row);
                }
            }
        }
        log.debug("Step executions for jobExecutionId={}: {}", jobExecutionId, results);
        return results;
    }

    /**
     * Gets total read count across all worker step executions for a job.
     */
    public int totalReadCount(long jobExecutionId, String workerStepPattern) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(SUM(READ_COUNT), 0) FROM BATCH_STEP_EXECUTION " +
                     "WHERE JOB_EXECUTION_ID = ? AND STEP_NAME LIKE ?")) {
            ps.setLong(1, jobExecutionId);
            ps.setString(2, workerStepPattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Deletes all data from target_records (for test isolation).
     */
    public void cleanTargetRecords() throws SQLException {
        cleanTable("target_records");
    }

    /**
     * Counts rows in enriched_transactions table.
     */
    public int countEnrichedTransactions() throws SQLException {
        return countRows("enriched_transactions");
    }

    /**
     * Deletes all data from enriched_transactions (for test isolation).
     */
    public void cleanEnrichedTransactions() throws SQLException {
        cleanTable("enriched_transactions");
    }

    /**
     * Deletes all data from CRUD tables (accounts, then customers — FK order).
     *
     * @throws SQLException on database errors
     */
    public void cleanCrudTables() throws SQLException {
        cleanTable(ACCOUNTS_TABLE);
        cleanTable(CUSTOMERS_TABLE);
    }

    /**
     * Checks whether the customers table exists in the database schema.
     *
     * @return true if the table exists
     * @throws SQLException on database errors
     */
    public boolean customersTableExists() throws SQLException {
        return tableExists(CUSTOMERS_TABLE);
    }

    /**
     * Checks whether the accounts table exists in the database schema.
     *
     * @return true if the table exists
     * @throws SQLException on database errors
     */
    public boolean accountsTableExists() throws SQLException {
        return tableExists(ACCOUNTS_TABLE);
    }

    /**
     * Checks if a table exists in the database schema.
     */
    public boolean tableExists(String tableName) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        }
    }

    /**
     * Counts all rows in the given table via {@code SELECT COUNT(*)}. Delegates to
     * {@link #queryCount(String)}.
     *
     * @param tableName the table to count rows in
     * @return the row count
     * @throws SQLException on database errors
     */
    private int countRows(String tableName) throws SQLException {
        return queryCount("SELECT COUNT(*) FROM " + tableName);
    }

    /**
     * Deletes all rows from the given table via {@code DELETE FROM}. Logs the number of deleted
     * rows at DEBUG level.
     *
     * @param tableName the table to clean
     * @throws SQLException on database errors
     */
    private void cleanTable(String tableName) throws SQLException {
        try (Connection conn = getConnection();
             var stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate("DELETE FROM " + tableName);
            log.debug("Cleaned {} | deleted={}", tableName, deleted);
        }
    }

    /**
     * Executes a single-column, single-row count query and returns the integer result. Used by
     * {@link #countRows(String)} and other counting methods.
     *
     * @param sql the SQL count query to execute
     * @return the count result
     * @throws SQLException on database errors
     */
    private int queryCount(String sql) throws SQLException {
        try (Connection conn = getConnection();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Opens a new JDBC connection using the configured URL, username, and password. No connection
     * pooling — each call creates a fresh connection. This is intentional: E2E verification makes
     * infrequent queries where connection setup overhead is negligible.
     *
     * @return a new JDBC connection
     * @throws SQLException if the connection cannot be established
     */
    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

}
