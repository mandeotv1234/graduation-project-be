package graduation_project_be.application.port.services;

import java.util.List;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import graduation_project_be.domain.models.SqlExecutionResult;

public interface ExamSchemaService {
    void createExamSchemaForStudent(Long examId, Long studentId);

    void resetSchema(String schemaName, boolean keepTables);

    void dropSchema(String schemaName);

    void loadTemplateIntoSchema(String schemaName, String ddlScript, String defaultDataScript);

    List<TableMetadata> extractMetadata(String schemaName);

    List<RoutineMetadata> extractRoutineMetadata(String schemaName);

    List<TriggerMetadata> extractTriggerMetadata(String schemaName);

    boolean schemaExists(String schemaName);

    SqlExecutionResult executeSql(String schemaName, String sql);

    SqlExecutionResult executeAdminSql(String sql);

    /**
     * Runs a multi-statement SQL batch as the schema-scoped DB user
     * (EXECUTE AS USER), with the same query timeout enforcement as
     * {@link #executeSql}, but WITHOUT the keyword-level
     * {@code validateStudentSql} restriction so that batches containing
     * {@code BEGIN TRY / BEGIN TRAN / DECLARE / THROW} (used by per-test-case
     * grading) are accepted.
     *
     * <p>Use this for grading test cases — it preserves schema isolation
     * (student SP cannot read other students' schemas) while still allowing
     * the per-TC transaction wrapper.
     */
    SqlExecutionResult executeSqlBatchAsSchemaUser(String schemaName, String batchSql);

    /**
     * Drops all schemas for a given exam (all students, all attempts).
     * Schema names matching the pattern exam_{examId}_% will be dropped.
     */
    void dropAllExamSchemas(Long examId);
}