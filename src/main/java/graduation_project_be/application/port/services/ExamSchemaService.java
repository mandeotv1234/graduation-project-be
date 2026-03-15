package graduation_project_be.application.port.services;

import java.util.List;
import java.util.Map;
import graduation_project_be.domain.models.TableMetadata;

public interface ExamSchemaService {
    void createExamSchemaForStudent(Long examId, Long studentId);

    void resetSchema(String schemaName);

    void dropSchema(String schemaName);

    void loadTemplateIntoSchema(String schemaName, String ddlScript, String defaultDataScript);

    List<TableMetadata> extractMetadata(String schemaName);

    List<Map<String, Object>> executeSql(String schemaName, String sql);
}