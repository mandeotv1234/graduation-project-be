package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.ExamSchemaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Slf4j
@Service
public class MsSqlExamSchemaService implements ExamSchemaService {

    private final JdbcTemplate jdbcTemplate;

    public MsSqlExamSchemaService(@Qualifier("examJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void createExamSchemaForStudent(Long examId, Long studentId) {
        String schemaName = String.format("exam_%d_student_%d", examId, studentId);
        ensureSchemaAndUser(schemaName);
    }

    /**
     * Creates schema + a DB user (WITHOUT LOGIN) mapped to that schema.
     * The user gets full permissions on its own schema.
     */
    private void ensureSchemaAndUser(String schemaName) {
        String userName = schemaName + "_user";

        try {
            // 1. Create schema if not exists
            String createSchema = String.format(
                    "IF NOT EXISTS (SELECT 1 FROM sys.schemas WHERE name = '%s') EXEC('CREATE SCHEMA [%s]')",
                    schemaName, schemaName);
            jdbcTemplate.execute(createSchema);

            // 2. Create user without login, mapped to schema as default
            String createUser = String.format(
                    "IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = '%s') " +
                            "CREATE USER [%s] WITHOUT LOGIN WITH DEFAULT_SCHEMA = [%s]",
                    userName, userName, schemaName);
            jdbcTemplate.execute(createUser);

            // 3. Grant permissions on the schema
            String grantSchema = String.format(
                    "GRANT ALTER, INSERT, SELECT, UPDATE, DELETE, EXECUTE, REFERENCES " +
                            "ON SCHEMA :: [%s] TO [%s]",
                    schemaName, userName);
            jdbcTemplate.execute(grantSchema);

            // 4. Grant DDL permissions
            String grantCreate = String.format(
                    "GRANT CREATE TABLE, CREATE PROCEDURE, CREATE FUNCTION TO [%s]",
                    userName);
            jdbcTemplate.execute(grantCreate);

            log.info("Ensured schema [{}] and user [{}] exist with full permissions", schemaName, userName);
        } catch (Exception e) {
            log.error("Failed to create schema/user for {}", schemaName, e);
            throw new RuntimeException("Failed to prepare exam schema: " + e.getMessage(), e);
        }
    }

    @Override
    public void resetSchema(String schemaName) {
        String userName = schemaName + "_user";

        try {
            jdbcTemplate.execute((Connection conn) -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("EXECUTE AS USER = '" + userName + "'");
                }

                try {
                    // 1. Drop all triggers
                    try (Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT t.name AS trigger_name, OBJECT_NAME(t.parent_id) AS table_name " +
                                            "FROM sys.triggers t JOIN sys.tables tb ON t.parent_id = tb.object_id " +
                                            "WHERE SCHEMA_NAME(tb.schema_id) = '" + schemaName + "'")) {
                        while (rs.next()) {
                            String trigger = rs.getString("trigger_name");
                            try (Statement drop = conn.createStatement()) {
                                drop.execute("DROP TRIGGER [" + schemaName + "].[" + trigger + "]");
                            }
                        }
                    }

                    // 2. Drop all foreign key constraints
                    try (Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT fk.name AS fk_name, OBJECT_NAME(fk.parent_object_id) AS table_name " +
                                            "FROM sys.foreign_keys fk JOIN sys.tables t ON fk.parent_object_id = t.object_id "
                                            +
                                            "WHERE SCHEMA_NAME(t.schema_id) = '" + schemaName + "'")) {
                        while (rs.next()) {
                            String fk = rs.getString("fk_name");
                            String table = rs.getString("table_name");
                            try (Statement drop = conn.createStatement()) {
                                drop.execute("ALTER TABLE [" + schemaName + "].[" + table + "] DROP CONSTRAINT [" + fk
                                        + "]");
                            }
                        }
                    }

                    // 3. Drop all tables
                    try (Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT name FROM sys.tables WHERE schema_id = SCHEMA_ID('" + schemaName + "')")) {
                        while (rs.next()) {
                            String table = rs.getString("name");
                            try (Statement drop = conn.createStatement()) {
                                drop.execute("DROP TABLE [" + schemaName + "].[" + table + "]");
                            }
                        }
                    }

                    // 4. Drop all procedures
                    try (Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT name FROM sys.procedures WHERE schema_id = SCHEMA_ID('" + schemaName
                                            + "')")) {
                        while (rs.next()) {
                            String proc = rs.getString("name");
                            try (Statement drop = conn.createStatement()) {
                                drop.execute("DROP PROCEDURE [" + schemaName + "].[" + proc + "]");
                            }
                        }
                    }

                    // 5. Drop all functions
                    try (Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT name FROM sys.objects WHERE schema_id = SCHEMA_ID('" + schemaName + "') " +
                                            "AND type IN ('FN','IF','TF')")) {
                        while (rs.next()) {
                            String func = rs.getString("name");
                            try (Statement drop = conn.createStatement()) {
                                drop.execute("DROP FUNCTION [" + schemaName + "].[" + func + "]");
                            }
                        }
                    }
                } finally {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("REVERT");
                    }
                }

                return null;
            });

            log.info("Reset schema [{}] — all objects dropped", schemaName);
        } catch (Exception e) {
            log.error("Failed to reset schema {}: {}", schemaName, e.getMessage());
            throw new RuntimeException("Failed to reset schema: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropSchema(String schemaName) {
        String userName = schemaName + "_user";

        try {
            // First reset all objects inside the schema
            resetSchema(schemaName);

            // Then drop the user and schema
            jdbcTemplate.execute((Connection conn) -> {
                try (Statement stmt = conn.createStatement()) {
                    // Drop user if exists
                    stmt.execute("IF EXISTS (SELECT 1 FROM sys.database_principals WHERE name = '" + userName + "') " +
                            "DROP USER [" + userName + "]");
                }
                try (Statement stmt = conn.createStatement()) {
                    // Drop schema if exists
                    stmt.execute("IF EXISTS (SELECT 1 FROM sys.schemas WHERE name = '" + schemaName + "') " +
                            "DROP SCHEMA [" + schemaName + "]");
                }
                return null;
            });

            log.info("Dropped schema [{}] and user [{}]", schemaName, userName);
        } catch (Exception e) {
            log.error("Failed to drop schema {}: {}", schemaName, e.getMessage());
            throw new RuntimeException("Failed to drop schema: " + e.getMessage(), e);
        }
    }

    @Override
    public void loadTemplateIntoSchema(String schemaName, String ddlScript, String defaultDataScript) {
        String userName = schemaName + "_user";
        ensureSchemaAndUser(schemaName);

        try {
            jdbcTemplate.execute((Connection conn) -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("EXECUTE AS USER = '" + userName + "'");
                }

                try {
                    if (ddlScript != null && !ddlScript.isBlank()) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute(ddlScript);
                        }
                        log.info("Loaded DDL into schema: {}", schemaName);
                    }

                    if (defaultDataScript != null && !defaultDataScript.isBlank()) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute(defaultDataScript);
                        }
                        log.info("Loaded default data into schema: {}", schemaName);
                    }
                } finally {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("REVERT");
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to load template into schema: {}", schemaName, e);
            throw new RuntimeException("Failed to load template into schema: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> executeSql(String schemaName, String sql) {
        String userName = schemaName + "_user";
        // Ensure schema + user exist (handles seed-data exams where
        // createExamSchemaForStudent was never called)
        ensureSchemaAndUser(schemaName);

        // Sanitize SQL — block privilege escalation keywords
        validateStudentSql(sql);

        try {
            return jdbcTemplate.execute((Connection conn) -> {
                List<Map<String, Object>> results = new ArrayList<>();

                // Switch execution context to the student's user (uses their default schema)
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("EXECUTE AS USER = '" + userName + "'");
                }

                try {
                    try (Statement stmt = conn.createStatement()) {
                        boolean isResultSet = stmt.execute(sql);

                        // Walk through ALL results using correct JDBC pattern
                        // (handles BEGIN TRY...CATCH, EXEC+SELECT, etc.)
                        while (true) {
                            if (isResultSet) {
                                try (ResultSet rs = stmt.getResultSet()) {
                                    ResultSetMetaData meta = rs.getMetaData();
                                    int colCount = meta.getColumnCount();
                                    while (rs.next()) {
                                        Map<String, Object> row = new LinkedHashMap<>();
                                        for (int i = 1; i <= colCount; i++) {
                                            row.put(meta.getColumnLabel(i), rs.getObject(i));
                                        }
                                        results.add(row);
                                    }
                                }
                            } else {
                                // Current result is an update count
                                int updateCount = stmt.getUpdateCount();
                                if (updateCount == -1) {
                                    // No more results of any kind
                                    break;
                                }
                            }
                            // Advance to next result (only call ONCE per iteration!)
                            isResultSet = stmt.getMoreResults();
                        }
                    }

                    // If no result set was returned (pure DDL/DML), return a status
                    if (results.isEmpty()) {
                        results.add(Map.of("result", "Statement executed successfully"));
                    }
                } finally {
                    // Always revert context back to original user
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("REVERT");
                    }
                }

                return results;
            });
        } catch (Exception e) {
            log.error("SQL execution error on schema [{}]: {}", schemaName, e.getMessage());
            throw new RuntimeException("SQL execution error: " + e.getMessage(), e);
        }
    }

    /**
     * Block SQL statements that could escalate privileges or escape the
     * sandboxed EXECUTE AS USER context.
     */
    private void validateStudentSql(String sql) {
        if (sql == null || sql.isBlank())
            return;

        String upper = sql.toUpperCase().replaceAll("\\s+", " ").trim();

        String[] blockedPatterns = {
                "REVERT", // escape EXECUTE AS context
                "EXECUTE AS", // switch to another user
                "GRANT ", // modify permissions
                "DENY ", // modify permissions
                "REVOKE ", // modify permissions
                "ALTER LOGIN", // modify logins
                "CREATE LOGIN", // create logins
                "DROP LOGIN", // drop logins
                "ALTER USER", // modify user
                "CREATE USER", // create user
                "DROP USER", // drop user
                "ALTER SCHEMA", // modify schema ownership
                "DROP SCHEMA", // drop schema
                "OPENROWSET", // external data access
                "OPENDATASOURCE", // external data access
                "XP_CMDSHELL", // OS command execution
                "SP_CONFIGURE", // server configuration
        };

        for (String blocked : blockedPatterns) {
            if (upper.contains(blocked)) {
                throw new SecurityException(
                        "SQL contains blocked statement: " + blocked + ". This operation is not allowed.");
            }
        }
    }
}
