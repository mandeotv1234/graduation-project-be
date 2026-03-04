package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ExamResult;

public interface ExamResultRepository {
    ExamResult save(ExamResult examResult);
}
