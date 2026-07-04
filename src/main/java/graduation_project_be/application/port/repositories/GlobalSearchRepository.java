package graduation_project_be.application.port.repositories;

import graduation_project_be.application.usecases.response.GlobalSearchResponse;

public interface GlobalSearchRepository {
    GlobalSearchResponse searchAll(String keyword, Long teacherId);
}
