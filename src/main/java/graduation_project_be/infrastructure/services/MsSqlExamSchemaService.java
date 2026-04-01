package graduation_project_be.infrastructure.services;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MsSqlExamSchemaService implements ExamSchemaService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Maximum time (in seconds) a student SQL query is allowed to run.
     * Prevents infinite loops, Cartesian products, and other long-running queries.
     */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

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
            // Multi-dataset grading may call reset before any executeSql call.
            // Ensure schema + user exist before impersonating with EXECUTE AS USER.
            ensureSchemaAndUser(schemaName);

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
                            while (stmt.getMoreResults() || stmt.getUpdateCount() != -1) {}
                        }
                        log.info("Loaded DDL into schema: {}", schemaName);
                    }

                    if (defaultDataScript != null && !defaultDataScript.isBlank()) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute(defaultDataScript);
                            while (stmt.getMoreResults() || stmt.getUpdateCount() != -1) {}
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
    public List<graduation_project_be.domain.models.TableMetadata> extractMetadata(String schemaName) {
        String sql = "SELECT t.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, " +
                "c.CHARACTER_MAXIMUM_LENGTH, c.IS_NULLABLE, " +
                "CASE WHEN kcu_pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END AS IsPrimaryKey, " +
                "fk.REFERENCED_TABLE_NAME AS ReferencedTable, " +
                "fk.REFERENCED_COLUMN_NAME AS ReferencedColumn " +
                "FROM INFORMATION_SCHEMA.TABLES t " +
                "JOIN INFORMATION_SCHEMA.COLUMNS c ON t.TABLE_NAME = c.TABLE_NAME AND t.TABLE_SCHEMA = c.TABLE_SCHEMA " +
                // Primary key
                "LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc_pk " +
                "    ON tc_pk.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc_pk.TABLE_NAME = t.TABLE_NAME AND tc_pk.CONSTRAINT_TYPE = 'PRIMARY KEY' " +
                "LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu_pk " +
                "    ON kcu_pk.CONSTRAINT_NAME = tc_pk.CONSTRAINT_NAME AND kcu_pk.COLUMN_NAME = c.COLUMN_NAME " +
                // Foreign key (resolve referenced table/column)
                "LEFT JOIN ( " +
                "    SELECT kcu.TABLE_SCHEMA, kcu.TABLE_NAME, kcu.COLUMN_NAME, " +
                "           kcu_ref.TABLE_NAME AS REFERENCED_TABLE_NAME, " +
                "           kcu_ref.COLUMN_NAME AS REFERENCED_COLUMN_NAME " +
                "    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu ON kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "    JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc ON rc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu_ref ON kcu_ref.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME AND kcu_ref.ORDINAL_POSITION = kcu.ORDINAL_POSITION " +
                "    WHERE tc.CONSTRAINT_TYPE = 'FOREIGN KEY' " +
                ") fk ON fk.TABLE_SCHEMA = t.TABLE_SCHEMA AND fk.TABLE_NAME = t.TABLE_NAME AND fk.COLUMN_NAME = c.COLUMN_NAME " +
                "WHERE t.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE' " +
                "ORDER BY t.TABLE_NAME, c.ORDINAL_POSITION";

        return jdbcTemplate.query(sql, ps -> ps.setString(1, schemaName), (rs) -> {
            Map<String, graduation_project_be.domain.models.TableMetadata> tableMap = new LinkedHashMap<>();

            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                String dataType = rs.getString("DATA_TYPE");
                int maxLength = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
                boolean isNullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                boolean isPrimaryKey = rs.getBoolean("IsPrimaryKey");
                String referencedTable = rs.getString("ReferencedTable");
                String referencedColumn = rs.getString("ReferencedColumn");
                boolean isForeignKey = referencedTable != null && !referencedTable.isBlank();

                // Format data type for UI readability
                String formattedDataType = formatDataType(dataType, maxLength);
                String rawDataType = buildRawDataType(dataType, maxLength);

                graduation_project_be.domain.models.TableMetadata table = tableMap.computeIfAbsent(tableName,
                        k -> graduation_project_be.domain.models.TableMetadata.builder()
                                .tableName(tableName)
                                .columns(new ArrayList<>())
                                .build());

                graduation_project_be.domain.models.TableMetadata.ColumnMetadata column = graduation_project_be.domain.models.TableMetadata.ColumnMetadata.builder()
                        .columnName(columnName)
                        .dataType(formattedDataType)
                        .rawDataType(rawDataType)
                        .isPrimaryKey(isPrimaryKey)
                        .isForeignKey(isForeignKey)
                        .referencesTable(referencedTable)
                        .referencesColumn(referencedColumn)
                        .isNullable(isNullable)
                        .build();

                table.getColumns().add(column);
            }

            return new ArrayList<>(tableMap.values());
        });
    }

    private String formatDataType(String dataType, int maxLength) {
        if (dataType == null) return "Unknown";
        String lower = dataType.toLowerCase();
        
        switch (lower) {
            case "int":
            case "bigint":
            case "smallint":
            case "tinyint":
                return "Số nguyên (" + lower + ")";
            case "decimal":
            case "numeric":
            case "float":
            case "real":
                return "Số thực (" + lower + ")";
            case "varchar":
            case "nvarchar":
            case "char":
            case "nchar":
                return maxLength > 0 ? "Chuỗi (" + maxLength + ")" : "Chuỗi (Max)";
            case "date":
                return "Ngày";
            case "datetime":
            case "datetime2":
                return "Ngày giờ";
            case "bit":
            case "boolean":
                return "Logic (Boolean)";
            case "text":
            case "ntext":
                return "Văn bản (Text)";
            default:
                return dataType;
        }
    }

    private String buildRawDataType(String dataType, int maxLength) {
        if (dataType == null) return "UNKNOWN";
        String upper = dataType.toUpperCase();
        if (maxLength > 0 && (upper.contains("CHAR") || upper.contains("BINARY"))) {
            return upper + "(" + maxLength + ")";
        } else if (maxLength == -1 && (upper.contains("CHAR") || upper.contains("BINARY"))) {
            return upper + "(MAX)";
        }
        return upper;
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
                        stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
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

    @Override
    public List<Map<String, Object>> executeAdminSql(String sql) {
        log.debug("Executing admin SQL: {}", sql);

        try {
            return jdbcTemplate.execute((Connection conn) -> {
                List<Map<String, Object>> results = new ArrayList<>();

                try (Statement stmt = conn.createStatement()) {
                    boolean isResultSet = stmt.execute(sql);

                    while (true) {
                        if (isResultSet) {
                            try (ResultSet rs = stmt.getResultSet()) {
                                if (rs != null) {
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
                            }
                        } else {
                            int updateCount = stmt.getUpdateCount();
                            if (updateCount == -1) {
                                break;
                            }
                        }
                        isResultSet = stmt.getMoreResults();
                    }

                    if (results.isEmpty()) {
                        results.add(Map.of("result", "Admin statement executed successfully"));
                    }
                }

                return results;
            });
        } catch (Exception e) {
            log.error("Admin SQL execution error: {}", e.getMessage());
            throw new RuntimeException("Admin SQL execution error: " + e.getMessage(), e);
        }
    }

    @Override
    public java.util.List<RoutineMetadata> extractRoutineMetadata(String schemaName) {
        String sql = "SELECT r.ROUTINE_NAME, r.ROUTINE_TYPE, r.DATA_TYPE AS RET_TYPE, " +
                "p.PARAMETER_MODE, p.PARAMETER_NAME, p.DATA_TYPE AS PARAM_TYPE " +
                "FROM INFORMATION_SCHEMA.ROUTINES r " +
                "LEFT JOIN INFORMATION_SCHEMA.PARAMETERS p ON r.ROUTINE_NAME = p.SPECIFIC_NAME AND r.ROUTINE_SCHEMA = p.SPECIFIC_SCHEMA " +
                "WHERE r.ROUTINE_SCHEMA = ? " +
                "ORDER BY r.ROUTINE_NAME, p.ORDINAL_POSITION";
        
        return jdbcTemplate.query(sql, ps -> ps.setString(1, schemaName), (rs) -> {
            Map<String, RoutineMetadata> routines = new LinkedHashMap<>();
            while (rs.next()) {
                String routineName = rs.getString("ROUTINE_NAME");
                String routineType = rs.getString("ROUTINE_TYPE");
                String retType = rs.getString("RET_TYPE");
                
                RoutineMetadata routine = routines.computeIfAbsent(routineName,
                        k -> RoutineMetadata.builder()
                                .routineName(routineName)
                                .routineType(routineType)
                                .dataType(retType)
                                .parameters(new ArrayList<>())
                                .build());
                
                String paramName = rs.getString("PARAMETER_NAME");
                if (paramName != null) {
                    RoutineMetadata.ParameterMetadata param = RoutineMetadata.ParameterMetadata.builder()
                            .parameterMode(rs.getString("PARAMETER_MODE"))
                            .parameterName(paramName)
                            .dataType(rs.getString("PARAM_TYPE"))
                            .build();
                    routine.getParameters().add(param);
                }
            }
            return new ArrayList<>(routines.values());
        });
    }

    @Override
    public java.util.List<TriggerMetadata> extractTriggerMetadata(String schemaName) {
        String sql = "SELECT t.name AS triggerName, tbl.name AS tableName, " +
                "te.type_desc, t.is_disabled, t.is_instead_of_trigger " +
                "FROM sys.triggers t " +
                "JOIN sys.tables tbl ON t.parent_id = tbl.object_id " +
                "JOIN sys.schemas s ON tbl.schema_id = s.schema_id " +
                "JOIN sys.trigger_events te ON t.object_id = te.object_id " +
                "WHERE s.name = ?";
                
        return jdbcTemplate.query(sql, ps -> ps.setString(1, schemaName), (rs) -> {
            Map<String, TriggerMetadata> triggers = new LinkedHashMap<>();
            while (rs.next()) {
                String name = rs.getString("triggerName");
                String typeDesc = rs.getString("type_desc"); // INSERT, UPDATE, DELETE
                
                TriggerMetadata tm = triggers.computeIfAbsent(name, 
                    k -> { 
                        try {
                           return TriggerMetadata.builder()
                                .triggerName(name)
                                .tableName(rs.getString("tableName"))
                                .isDisabled(rs.getBoolean("is_disabled"))
                                .isInsteadOf(rs.getBoolean("is_instead_of_trigger"))
                                .isAfter(!rs.getBoolean("is_instead_of_trigger"))
                                .build();
                        } catch(Exception e) { return null; }
                    });
                
                if (tm != null && typeDesc != null) {
                    if (typeDesc.equalsIgnoreCase("INSERT")) tm.setInsert(true);
                    if (typeDesc.equalsIgnoreCase("UPDATE")) tm.setUpdate(true);
                    if (typeDesc.equalsIgnoreCase("DELETE")) tm.setDelete(true);
                }
            }
            return new ArrayList<>(triggers.values());
        });
    }
}

