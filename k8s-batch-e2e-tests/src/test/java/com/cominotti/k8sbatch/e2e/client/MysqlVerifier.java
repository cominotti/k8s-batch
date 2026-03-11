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

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MysqlVerifier(int localPort, String database, String username, String password) {
        this.jdbcUrl = "jdbc:mysql://localhost:" + localPort + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true";
        this.username = username;
        this.password = password;
        log.info("MysqlVerifier configured | jdbcUrl={}", jdbcUrl);
    }

    /**
     * Counts rows in target_records table.
     */
    public int countTargetRecords() throws SQLException {
        return queryCount("SELECT COUNT(*) FROM target_records");
    }

    /**
     * Counts step executions matching a name pattern.
     */
    public int countStepExecutions(String stepNamePattern) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME LIKE ?")) {
            ps.setString(1, stepNamePattern);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Queries step executions for a job execution.
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
        try (Connection conn = getConnection();
             var stmt = conn.createStatement()) {
            int deleted = stmt.executeUpdate("DELETE FROM target_records");
            log.debug("Cleaned target_records | deleted={}", deleted);
        }
    }

    private int queryCount(String sql) throws SQLException {
        try (Connection conn = getConnection();
             var stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

}
