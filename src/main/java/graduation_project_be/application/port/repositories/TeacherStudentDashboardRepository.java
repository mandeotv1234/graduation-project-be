package graduation_project_be.application.port.repositories;

import graduation_project_be.application.usecases.response.GetStudentDashboardResponse;

public interface TeacherStudentDashboardRepository {
    GetStudentDashboardResponse getDashboard(Long teacherId, Long studentId);
}
