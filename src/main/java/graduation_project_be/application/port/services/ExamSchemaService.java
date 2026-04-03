package graduation_project_be.application.port.services;

import java.util.List;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.domain.models.RoutineMetadata;
import graduation_project_be.domain.models.TriggerMetadata;
import graduation_project_be.domain.models.SqlExecutionResult;

public interface ExamSchemaService {
    void createExamSchemaForStudent(Long examId, Long studentId);

    void resetSchema(String schemaName);

    void dropSchema(String schemaName);

    void loadTemplateIntoSchema(String schemaName, String ddlScript, String defaultDataScript);

    List<TableMetadata> extractMetadata(String schemaName);

    List<RoutineMetadata> extractRoutineMetadata(String schemaName);

    List<TriggerMetadata> extractTriggerMetadata(String schemaName);

    SqlExecutionResult executeSql(String schemaName, String sql);

    SqlExecutionResult executeAdminSql(String sql);
}