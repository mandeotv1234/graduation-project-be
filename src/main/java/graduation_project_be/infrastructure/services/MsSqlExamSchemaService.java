package graduation_project_be.infrastructure.services;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.SqlExecutionResult;
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
    private static final int QUERY_TIMEOUT_SECONDS = 10;

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

            log.info("Đã đảm bảo schema [{}] và user [{}] tồn tại với đầy đủ quyền", schemaName, userName);
        } catch (Exception e) {
            log.error("Không thể tạo schema/user cho {}", schemaName, e);
            throw new RuntimeException("Không thể chuẩn bị schema bài thi: " + e.getMessage(), e);
        }
    }

    @Override
    public void resetSchema(String schemaName, boolean keepTables) {
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
                    if (!keepTables) {
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
                                    drop.execute(
                                            "ALTER TABLE [" + schemaName + "].[" + table + "] DROP CONSTRAINT [" + fk
                                                    + "]");
                                }
                            }
                        }

                        // 3. Drop all tables
                        try (Statement stmt = conn.createStatement();
                                ResultSet rs = stmt.executeQuery(
                                        "SELECT name FROM sys.tables WHERE schema_id = SCHEMA_ID('" + schemaName
                                                + "')")) {
                            while (rs.next()) {
                                String table = rs.getString("name");
                                try (Statement drop = conn.createStatement()) {
                                    drop.execute("DROP TABLE [" + schemaName + "].[" + table + "]");
                                }
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

            log.info("Đã reset schema [{}] — toàn bộ object đã bị xóa", schemaName);
        } catch (Exception e) {
            log.error("Không thể reset schema {}: {}", schemaName, e.getMessage());
            throw new RuntimeException("Không thể reset schema: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropSchema(String schemaName) {
        String userName = schemaName + "_user";

        try {
            // First reset all objects inside the schema
            resetSchema(schemaName, false);

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

            log.info("Đã xóa schema [{}] và user [{}]", schemaName, userName);
        } catch (Exception e) {
            log.error("Không thể xóa schema {}: {}", schemaName, e.getMessage());
            throw new RuntimeException("Không thể xóa schema: " + e.getMessage(), e);
        }
    }

    @Override
    public void dropAllExamSchemas(Long examId) {
        String prefix = String.format("exam_%d_", examId);
        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT name FROM sys.schemas WHERE name LIKE ?",
                String.class,
                prefix + "%");
        for (String schemaName : schemas) {
            try {
                dropSchema(schemaName);
                log.info("Đã xóa schema [{}] cho exam {}", schemaName, examId);
            } catch (Exception e) {
                log.warn("Không thể xóa schema [{}]: {}", schemaName, e.getMessage());
            }
        }
        log.info("Đã xóa {} schema cho exam {}", schemas.size(), examId);
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
                        for (String ddlBatch : splitExecutableBatches(ddlScript)) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute(ddlBatch);
                                while (stmt.getMoreResults() || stmt.getUpdateCount() != -1) {
                                }
                            }
                        }
                        log.info("Đã nạp DDL vào schema: {}", schemaName);
                    }

                    if (defaultDataScript != null && !defaultDataScript.isBlank()) {
                        for (String dataBatch : splitExecutableBatches(defaultDataScript)) {
                            try (Statement stmt = conn.createStatement()) {
                                stmt.execute(dataBatch);
                                while (stmt.getMoreResults() || stmt.getUpdateCount() != -1) {
                                }
                            }
                        }
                        log.info("Đã nạp dữ liệu mặc định vào schema: {}", schemaName);
                    }
                } finally {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("REVERT");
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Không thể nạp template vào schema: {}", schemaName, e);
            throw new RuntimeException("Không thể nạp template vào schema: " + e.getMessage(), e);
        }
    }

    @Override
    public List<graduation_project_be.domain.models.TableMetadata> extractMetadata(String schemaName) {
        String sql = "SELECT t.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, " +
                "c.CHARACTER_MAXIMUM_LENGTH, c.IS_NULLABLE, " +
                "CASE WHEN kcu_pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END AS IsPrimaryKey, " +
                "CASE WHEN kcu_uq.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END AS IsUnique, " +
                "CASE WHEN sc.is_identity = 1 THEN 1 ELSE 0 END AS IsIdentity, " +
                "fk.REFERENCED_TABLE_NAME AS ReferencedTable, " +
                "fk.REFERENCED_COLUMN_NAME AS ReferencedColumn " +
                "FROM INFORMATION_SCHEMA.TABLES t " +
                "JOIN INFORMATION_SCHEMA.COLUMNS c ON t.TABLE_NAME = c.TABLE_NAME AND t.TABLE_SCHEMA = c.TABLE_SCHEMA "
                +
                // Primary key
                "LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc_pk " +
                "    ON tc_pk.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc_pk.TABLE_NAME = t.TABLE_NAME AND tc_pk.CONSTRAINT_TYPE = 'PRIMARY KEY' "
                +
                "LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu_pk " +
                "    ON kcu_pk.CONSTRAINT_SCHEMA = tc_pk.CONSTRAINT_SCHEMA " +
                "    AND kcu_pk.CONSTRAINT_NAME = tc_pk.CONSTRAINT_NAME " +
                "    AND kcu_pk.TABLE_SCHEMA = t.TABLE_SCHEMA " +
                "    AND kcu_pk.TABLE_NAME = t.TABLE_NAME " +
                "    AND kcu_pk.COLUMN_NAME = c.COLUMN_NAME " +
                // Unique constraint
                "LEFT JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc_uq " +
                "    ON tc_uq.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc_uq.TABLE_NAME = t.TABLE_NAME AND tc_uq.CONSTRAINT_TYPE = 'UNIQUE' "
                +
                "LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu_uq " +
                "    ON kcu_uq.CONSTRAINT_SCHEMA = tc_uq.CONSTRAINT_SCHEMA " +
                "    AND kcu_uq.CONSTRAINT_NAME = tc_uq.CONSTRAINT_NAME " +
                "    AND kcu_uq.TABLE_SCHEMA = t.TABLE_SCHEMA " +
                "    AND kcu_uq.TABLE_NAME = t.TABLE_NAME " +
                "    AND kcu_uq.COLUMN_NAME = c.COLUMN_NAME " +
                // SQL Server identity
                "LEFT JOIN sys.tables st ON st.name = t.TABLE_NAME AND SCHEMA_NAME(st.schema_id) = t.TABLE_SCHEMA " +
                "LEFT JOIN sys.columns sc ON sc.object_id = st.object_id AND sc.name = c.COLUMN_NAME " +
                // Foreign key (resolve referenced table/column)
                "LEFT JOIN ( " +
                "    SELECT kcu.TABLE_SCHEMA, kcu.TABLE_NAME, kcu.COLUMN_NAME, " +
                "           kcu_ref.TABLE_NAME AS REFERENCED_TABLE_NAME, " +
                "           kcu_ref.COLUMN_NAME AS REFERENCED_COLUMN_NAME " +
                "    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
                "    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu " +
                "        ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA " +
                "        AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "        AND kcu.TABLE_SCHEMA = tc.TABLE_SCHEMA " +
                "        AND kcu.TABLE_NAME = tc.TABLE_NAME " +
                "    JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc " +
                "        ON rc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA " +
                "        AND rc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                "    JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu_ref " +
                "        ON kcu_ref.CONSTRAINT_SCHEMA = rc.UNIQUE_CONSTRAINT_SCHEMA " +
                "        AND kcu_ref.CONSTRAINT_NAME = rc.UNIQUE_CONSTRAINT_NAME " +
                "        AND kcu_ref.ORDINAL_POSITION = kcu.ORDINAL_POSITION "
                +
                "    WHERE tc.CONSTRAINT_TYPE = 'FOREIGN KEY' " +
                ") fk ON fk.TABLE_SCHEMA = t.TABLE_SCHEMA AND fk.TABLE_NAME = t.TABLE_NAME AND fk.COLUMN_NAME = c.COLUMN_NAME "
                +
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
                boolean isUnique = rs.getBoolean("IsUnique");
                boolean isIdentity = rs.getBoolean("IsIdentity");
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

                graduation_project_be.domain.models.TableMetadata.ColumnMetadata column = graduation_project_be.domain.models.TableMetadata.ColumnMetadata
                        .builder()
                        .columnName(columnName)
                        .dataType(formattedDataType)
                        .rawDataType(rawDataType)
                        .isPrimaryKey(isPrimaryKey)
                        // For composite PK, each single column is NOT individually unique.
                        // We finalize PK-derived uniqueness in a second pass after reading all columns.
                        .isUnique(isUnique)
                        .isAutoIncrement(isIdentity)
                        .isForeignKey(isForeignKey)
                        .referencesTable(referencedTable)
                        .referencesColumn(referencedColumn)
                        .isNullable(isNullable)
                        .build();

                table.getColumns().add(column);
            }

            List<graduation_project_be.domain.models.TableMetadata> tables = new ArrayList<>(tableMap.values());

            // Apply PK-derived uniqueness only for single-column primary keys.
            for (graduation_project_be.domain.models.TableMetadata table : tables) {
                long pkColumnCount = table.getColumns().stream()
                        .filter(graduation_project_be.domain.models.TableMetadata.ColumnMetadata::isPrimaryKey)
                        .count();

                if (pkColumnCount == 1) {
                    table.getColumns().forEach(column -> {
                        if (column.isPrimaryKey()) {
                            column.setUnique(true);
                        }
                    });
                }
            }

            return tables;
        });
    }

    private String formatDataType(String dataType, int maxLength) {
        if (dataType == null)
            return "unknown";
        String lower = dataType.toLowerCase();

        switch (lower) {
            case "int":
            case "bigint":
            case "smallint":
            case "tinyint":
            case "decimal":
            case "numeric":
            case "float":
            case "real":
            case "date":
            case "datetime":
            case "datetime2":
            case "bit":
            case "boolean":
            case "text":
            case "ntext":
                return lower;
            case "varchar":
            case "nvarchar":
            case "char":
            case "nchar":
                return maxLength > 0 ? lower + "(" + maxLength + ")" : lower + "(max)";
            default:
                return dataType;
        }
    }

    private String buildRawDataType(String dataType, int maxLength) {
        if (dataType == null)
            return "UNKNOWN";
        String upper = dataType.toUpperCase();
        if (maxLength > 0 && (upper.contains("CHAR") || upper.contains("BINARY"))) {
            return upper + "(" + maxLength + ")";
        } else if (maxLength == -1 && (upper.contains("CHAR") || upper.contains("BINARY"))) {
            return upper + "(MAX)";
        }
        return upper;
    }

    @Override
    public boolean schemaExists(String schemaName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys.schemas WHERE name = ?",
                Integer.class,
                schemaName);
        return count != null && count > 0;
    }

    @Override
    public SqlExecutionResult executeSql(String schemaName, String sql) {
        String userName = schemaName + "_user";
        // Ensure schema + user exist (handles seed-data exams where
        // createExamSchemaForStudent was never called)
        ensureSchemaAndUser(schemaName);

        // Sanitize SQL — block privilege escalation keywords
        List<String> executableBatches = splitExecutableBatches(sql);
        for (String batch : executableBatches) {
            validateStudentSql(batch);
        }

        try {
            return jdbcTemplate.execute((Connection conn) -> {
                List<Map<String, Object>> results = new ArrayList<>();
                List<String> resultColumns = new ArrayList<>();
                int totalUpdateCount = 0;
                boolean hasUpdateCount = false;

                // Switch execution context to the student's user (uses their default schema)
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("EXECUTE AS USER = '" + userName + "'");
                }

                List<String> printMessages = new ArrayList<>();
                try {
                    long absoluteTimeoutMs = System.currentTimeMillis() + (QUERY_TIMEOUT_SECONDS * 1000);

                    for (String batch : executableBatches) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                            stmt.setMaxRows(1000);

                            int[] updateCountState = new int[] { totalUpdateCount };
                            boolean[] hasUpdateCountState = new boolean[] { hasUpdateCount };

                            executeSingleBatch(stmt, batch, results, resultColumns, updateCountState,
                                    hasUpdateCountState, absoluteTimeoutMs);

                            totalUpdateCount = updateCountState[0];
                            hasUpdateCount = hasUpdateCountState[0];

                            // Capture PRINT output (T-SQL PRINT / RAISERROR sev<=10) before
                            // the Statement is closed by try-with-resources.
                            printMessages.addAll(collectPrintMessages(stmt));
                        }
                    }

                    String statusMessage = null;
                    if (results.isEmpty()) {
                        if (hasUpdateCount && totalUpdateCount >= 0) {
                            statusMessage = "(" + totalUpdateCount + " row(s) affected)";
                        } else {
                            statusMessage = "Các lệnh đã chạy thành công.";
                        }
                    }

                    return SqlExecutionResult.builder()
                            .resultSet(results)
                            .columns(resultColumns)
                            .rowCount(results.size())
                            .statusMessage(statusMessage)
                            .printMessages(printMessages)
                            .build();
                } finally {
                    // Always revert context back to original user
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("REVERT");
                    }
                }
            });
        } catch (Exception e) {
            log.error("Lỗi thực thi SQL trên schema [{}]: {}", schemaName, e.getMessage());
            throw new RuntimeException("Lỗi thực thi SQL: " + e.getMessage(), e);
        }
    }

    private void executeSingleBatch(
            Statement stmt,
            String sql,
            List<Map<String, Object>> results,
            List<String> resultColumns,
            int[] totalUpdateCount,
            boolean[] hasUpdateCount,
            long absoluteTimeoutMs) {
        CompletableFuture<Boolean> executeFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return stmt.execute(sql);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });

        boolean isResultSet;
        try {
            long remainingMs = Math.max(1, absoluteTimeoutMs - System.currentTimeMillis());
            isResultSet = executeFuture.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            try {
                stmt.cancel();
            } catch (Exception ignore) {
            }
            throw new RuntimeException(
                    "Truy vấn chạy quá thời gian tối đa " + QUERY_TIMEOUT_SECONDS + " giây.");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Lỗi thực thi SQL: " + cause.getMessage(), cause);
        }

        // Walk through ALL results using correct JDBC pattern
        // (handles BEGIN TRY...CATCH, EXEC+SELECT, etc.)
        while (true) {
            if (System.currentTimeMillis() > absoluteTimeoutMs) {
                try {
                    stmt.cancel();
                } catch (Exception ignore) {
                }
                throw new RuntimeException("Xử lý truy vấn vượt quá thời gian tối đa "
                        + QUERY_TIMEOUT_SECONDS + " giây.");
            }

            if (isResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    if (rs != null) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        if (resultColumns.isEmpty() && colCount > 0) {
                            for (int i = 1; i <= colCount; i++) {
                                resultColumns.add(meta.getColumnLabel(i));
                            }
                        }
                        while (rs.next()) {
                            if (System.currentTimeMillis() > absoluteTimeoutMs) {
                                try {
                                    stmt.cancel();
                                } catch (Exception ignore) {
                                }
                                throw new RuntimeException("Lấy result set vượt quá thời gian tối đa "
                                        + QUERY_TIMEOUT_SECONDS + " giây.");
                            }

                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= colCount; i++) {
                                row.put(meta.getColumnLabel(i), rs.getObject(i));
                            }
                            results.add(row);
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("SQL result processing error: " + e.getMessage(), e);
                }
            } else {
                int updateCount;
                try {
                    updateCount = stmt.getUpdateCount();
                } catch (Exception e) {
                    throw new RuntimeException("SQL update count processing error: " + e.getMessage(), e);
                }
                if (updateCount == -1) {
                    break;
                }
                totalUpdateCount[0] += updateCount;
                hasUpdateCount[0] = true;
            }

            try {
                isResultSet = stmt.getMoreResults();
            } catch (Exception e) {
                throw new RuntimeException("SQL result processing error: " + e.getMessage(), e);
            }
        }
    }

    private List<String> splitExecutableBatches(String sqlScript) {
        if (sqlScript == null || sqlScript.isBlank()) {
            return List.of();
        }

        String normalized = sqlScript
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();

        List<String> batches = new ArrayList<>();
        for (String goBatch : normalized.split("(?im)^\\s*GO\\s*;?\\s*$")) {
            for (String batch : splitBatchBeforeCreateRoutine(goBatch)) {
                String executable = batch.trim();
                if (!executable.isBlank()) {
                    batches.add(executable);
                }
            }
        }
        return batches;
    }

    private List<String> splitBatchBeforeCreateRoutine(String batch) {
        if (batch == null || batch.isBlank()) {
            return List.of();
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)\\bCREATE\\s+(?:OR\\s+ALTER\\s+)?(?:PROCEDURE|PROC|FUNCTION|TRIGGER)\\b")
                .matcher(batch);
        if (!matcher.find()) {
            return List.of(batch);
        }

        String prefix = batch.substring(0, matcher.start()).trim();
        String routine = batch.substring(matcher.start()).trim();
        if (prefix.isBlank()) {
            return List.of(routine);
        }
        return List.of(prefix, routine);
    }

    /**
     * Multi-statement batch executor used by per-test-case grading.
     *
     * <p>Differs from {@link #executeSql} in two ways:
     * <ul>
     *   <li>Does NOT call {@code validateStudentSql} — grading batches contain
     *       {@code BEGIN TRY / BEGIN TRAN / DECLARE / THROW} which the keyword
     *       blocklist would reject (e.g. THROW is not in the allowed-starters
     *       list). The batch is built by trusted server-side code, not student
     *       input, so input validation is unnecessary.</li>
     *   <li>Statement context is impersonated to the schema's DB user (same as
     *       executeSql) so that any student-defined routine called inside the
     *       batch runs with that user's restricted permissions, NOT the admin
     *       connection's permissions. This preserves cross-schema isolation
     *       (the student's SP cannot read other students' schemas).</li>
     * </ul>
     *
     * <p>Has the same hard timeout as {@link #executeSql} so that a runaway
     * student routine (infinite loop, deadlock, WAITFOR) cannot hang the
     * grading worker.
     */
    @Override
    public SqlExecutionResult executeSqlBatchAsSchemaUser(String schemaName, String batchSql) {
        String userName = schemaName + "_user";
        ensureSchemaAndUser(schemaName);

        try {
            return jdbcTemplate.execute((Connection conn) -> {
                List<Map<String, Object>> results = new ArrayList<>();
                List<String> resultColumns = new ArrayList<>();
                int totalUpdateCount = 0;
                boolean hasUpdateCount = false;

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("EXECUTE AS USER = '" + userName + "'");
                }

                List<String> printMessages = new ArrayList<>();
                try {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        stmt.setMaxRows(1000);

                        long absoluteTimeoutMs = System.currentTimeMillis() + (QUERY_TIMEOUT_SECONDS * 1000);
                        int[] updateCountState = new int[] { totalUpdateCount };
                        boolean[] hasUpdateCountState = new boolean[] { hasUpdateCount };

                        executeSingleBatch(stmt, batchSql, results, resultColumns, updateCountState,
                                hasUpdateCountState, absoluteTimeoutMs);

                        totalUpdateCount = updateCountState[0];
                        hasUpdateCount = hasUpdateCountState[0];

                        printMessages.addAll(collectPrintMessages(stmt));
                    }

                    String statusMessage = null;
                    if (results.isEmpty()) {
                        if (hasUpdateCount && totalUpdateCount >= 0) {
                            statusMessage = "(" + totalUpdateCount + " row(s) affected)";
                        } else {
                            statusMessage = "Batch đã chạy thành công.";
                        }
                    }

                    return SqlExecutionResult.builder()
                            .resultSet(results)
                            .columns(resultColumns)
                            .rowCount(results.size())
                            .statusMessage(statusMessage)
                            .printMessages(printMessages)
                            .build();
                } finally {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("REVERT");
                    } catch (Exception e) {
                        log.warn("REVERT thất bại cho schema [{}]: {}", schemaName, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            log.error("Lỗi thực thi batch SQL trên schema [{}]: {}", schemaName, e.getMessage());
            throw new RuntimeException("Lỗi thực thi SQL: " + e.getMessage(), e);
        }
    }

    /**
     * Walks the SQLWarning chain attached to a Statement and collects messages.
     * MSSQL JDBC driver delivers each T-SQL {@code PRINT} statement (and
     * {@code RAISERROR ... WITH SEVERITY 0..10}) as one SQLWarning entry.
     * Higher severity is delivered as a SQLException, which we don't capture here.
     *
     * <p>Used by gradeByTestCases when verification_type = PRINT_OUTPUT.
     * Returns an empty list if there are no warnings or if reading them throws
     * (we never want to break grading because of warning-extraction bugs).
     */
    private List<String> collectPrintMessages(Statement stmt) {
        List<String> messages = new ArrayList<>();
        try {
            SQLWarning w = stmt.getWarnings();
            while (w != null) {
                String msg = w.getMessage();
                if (msg != null && !msg.isBlank()) {
                    messages.add(msg);
                }
                w = w.getNextWarning();
            }
            stmt.clearWarnings();
        } catch (Exception e) {
            log.warn("Không thể thu thập thông báo PRINT từ câu lệnh: {}", e.getMessage());
        }
        return messages;
    }

    /**
     * Block SQL statements that could escalate privileges or escape the
     * sandboxed EXECUTE AS USER context.
     */
    private void validateStudentSql(String sql) {
        if (sql == null || sql.isBlank())
            return;

        // Strip comments out for validation
        String sqlWithoutComments = sql.replaceAll("(?m)--.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
        String upper = sqlWithoutComments.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();

        if (upper.isEmpty()) {
            return;
        }

        // Validate that the query starts with a recognized keyword.
        // If not, SQL Server will implicitly try to EXEC it as a stored procedure.
        String firstToken = upper.split(" ")[0];
        List<String> allowedStarters = List.of(
                "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "TRUNCATE",
                "EXEC", "EXECUTE", "DECLARE", "WITH", "SET", "MERGE", "BEGIN", "IF", "WHILE");

        boolean isValidStart = allowedStarters.stream().anyMatch(firstToken::equals);
        if (!isValidStart) {
            throw new IllegalArgumentException(
                    "Lỗi cú pháp: Lệnh SQL không hợp lệ. Vui lòng kiểm tra lại từ khoá đầu tiên (có thể bạn gõ sai chính tả như '"
                            + firstToken + "', hệ thống không tìm thấy lệnh này).");
        }

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
                "USE ", // database switching
                "DROP DATABASE", // drop db
                "ALTER DATABASE", // alter db
                "BACKUP DATABASE", // backup
                "RESTORE DATABASE", // restore
                "DBCC ", // database console commands
                "KILL ", // kill processes
                "SHUTDOWN", // shutdown server
                "SP_OACREATE", // COM objects execution
                "MAXRECURSION 0", // infinite recursion
                "WAITFOR DELAY" // artificial delay
        };

        for (String blocked : blockedPatterns) {
            if (upper.contains(blocked)) {
                throw new SecurityException(
                        "SQL contains blocked statement: " + blocked + ". This operation is not allowed.");
            }
        }
    }

    @Override
    public SqlExecutionResult executeAdminSql(String sql) {
        long absoluteTimeoutMs = System.currentTimeMillis() + (QUERY_TIMEOUT_SECONDS * 1000L);
        try {
            return jdbcTemplate.execute((Connection conn) -> {
                List<Map<String, Object>> results = new ArrayList<>();
                List<String> resultColumns = new ArrayList<>();
                int totalUpdateCount = 0;
                boolean hasUpdateCount = false;

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
                            totalUpdateCount += updateCount;
                            hasUpdateCount = true;
                        }
                        isResultSet = stmt.getMoreResults();
                    }

                    // Capture PRINT output before the Statement closes.
                    List<String> printMessages = collectPrintMessages(stmt);

                    String statusMessage = null;
                    if (results.isEmpty()) {
                        if (hasUpdateCount && totalUpdateCount >= 0) {
                            statusMessage = "(" + totalUpdateCount + " row(s) affected)";
                        } else {
                            statusMessage = "Câu lệnh admin đã chạy thành công.";
                        }
                    }

                    return SqlExecutionResult.builder()
                            .resultSet(results)
                            .rowCount(results.size())
                            .statusMessage(statusMessage)
                            .printMessages(printMessages)
                            .build();
                }
            });
        } catch (Exception e) {
            log.error("Lỗi thực thi SQL admin: {}", e.getMessage());
            throw new RuntimeException("Lỗi thực thi SQL admin: " + e.getMessage(), e);
        }
    }

    @Override
    public java.util.List<RoutineMetadata> extractRoutineMetadata(String schemaName) {
        String sql = "SELECT r.ROUTINE_NAME, r.ROUTINE_TYPE, r.DATA_TYPE AS RET_TYPE, " +
                "p.PARAMETER_MODE, p.PARAMETER_NAME, p.DATA_TYPE AS PARAM_TYPE, p.ORDINAL_POSITION " +
                "FROM INFORMATION_SCHEMA.ROUTINES r " +
                "LEFT JOIN INFORMATION_SCHEMA.PARAMETERS p ON r.ROUTINE_NAME = p.SPECIFIC_NAME AND r.ROUTINE_SCHEMA = p.SPECIFIC_SCHEMA "
                +
                "WHERE r.ROUTINE_SCHEMA = ? " +
                "AND (p.ORDINAL_POSITION IS NULL OR p.ORDINAL_POSITION > 0) " +
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
                            } catch (Exception e) {
                                return null;
                            }
                        });

                if (tm != null && typeDesc != null) {
                    if (typeDesc.equalsIgnoreCase("INSERT"))
                        tm.setInsert(true);
                    if (typeDesc.equalsIgnoreCase("UPDATE"))
                        tm.setUpdate(true);
                    if (typeDesc.equalsIgnoreCase("DELETE"))
                        tm.setDelete(true);
                }
            }
            return new ArrayList<>(triggers.values());
        });
    }
}
