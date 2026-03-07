package graduation_project_be.application.port.services;

import java.util.List;
import java.util.Map;

public interface ExamSchemaService {
    void createExamSchemaForStudent(Long examId, Long studentId);

    void resetSchema(String schemaName);

    void dropSchema(String schemaName);

    void loadTemplateIntoSchema(String schemaName, String ddlScript, String defaultDataScript);

    List<Map<String, Object>> executeSql(String schemaName, String sql);
}