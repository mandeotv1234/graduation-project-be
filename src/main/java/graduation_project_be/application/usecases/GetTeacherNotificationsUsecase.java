package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.TeacherNotificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.GetTeacherNotificationsRequest;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.application.usecases.response.TeacherNotificationResponse;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.TeacherNotification;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetTeacherNotificationsUsecase {

    private final TeacherNotificationRepository teacherNotificationRepository;
    private final CurrentUserService currentUserService;

    public PaginationResponse<TeacherNotificationResponse> execute(GetTeacherNotificationsRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        int page = request.page() < 1 ? 0 : request.page() - 1;
        int size = request.size() < 1 ? 10 : request.size();

        PaginationParams params = PaginationParams.of(page, size);

        PaginatedResult<TeacherNotification> result = teacherNotificationRepository.findByTeacherId(teacherId, params);

        List<TeacherNotificationResponse> responses = result.getData().stream()
                .map(TeacherNotificationResponse::fromModel)
                .toList();

        return PaginationResponse.valueOf(
                responses,
                PaginationResponse.PaginationMeta.valueOf(page, size, result.getPagination().getTotal()));
    }
}
