package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.usecases.request.GetStudentsInClassRequest;
import graduation_project_be.application.usecases.response.GetStudentsInClassResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.domain.models.PaginationParams;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.ClassEnrollment;
import lombok.RequiredArgsConstructor;


import java.util.List;


@RequiredArgsConstructor
public class GetStudentsInClassUsecase {

    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public PaginationResponse<GetStudentsInClassResponse> execute(GetStudentsInClassRequest request) {

        Long currentUserId = currentUserService.getCurrentUserId();
        boolean isTeacherOfClass = classRepository.existsByIdAndTeacherId(request.classId(), currentUserId);
        if (!isTeacherOfClass) {
            throw new UnauthorizedException("User is not the teacher of this class");
        }

        List<ClassEnrollment> enrollments =
                classEnrollmentRepository.findByClassId(request.classId());


        List<Long> studentIds = enrollments.stream()
                .map(ClassEnrollment::getStudentId)
                .toList();

        PaginationParams paginationParams = request.getPaginationParams();

        int page = paginationParams.getPage() < 1 ? 0 : paginationParams.getPage() - 1;
        paginationParams.setPage(page);
        
        int limit = paginationParams.getSize();
        int offset = page * limit;

        List<User> students = userRepository.findByIdIn(studentIds, limit, offset);

        int totalCount = studentIds.size();
       
        List<GetStudentsInClassResponse> studentResponses = students.stream()
                .map(GetStudentsInClassResponse::fromModel)
                .toList();

        return PaginationResponse.valueOf(
                studentResponses,
                PaginationResponse.PaginationMeta.valueOf(
                        paginationParams.getPage(),
                        paginationParams.getSize(),
                        totalCount));
    }
}
