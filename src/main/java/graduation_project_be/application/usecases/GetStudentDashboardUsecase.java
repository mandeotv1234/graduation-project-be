package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.TeacherStudentDashboardRepository;
import graduation_project_be.application.usecases.request.GetStudentDashboardRequest;
import graduation_project_be.application.usecases.response.GetStudentDashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetStudentDashboardUsecase {

    private final TeacherStudentDashboardRepository dashboardRepository;

    @Transactional(readOnly = true)
    public GetStudentDashboardResponse execute(GetStudentDashboardRequest request) {
        return dashboardRepository.getDashboard(request.getTeacherId(), request.getStudentId());
    }
}
