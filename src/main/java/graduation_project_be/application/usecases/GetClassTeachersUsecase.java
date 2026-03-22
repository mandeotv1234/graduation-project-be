package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.TeacherClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.GetClassTeachersResponse;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetClassTeachersUsecase {

    private final ClassRepository classRepository;
    private final TeacherClassRepository teacherClassRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public List<GetClassTeachersResponse> execute(Long classId) {
        Class clazz = classRepository.findById(classId);

        Long currentUserId = currentUserService.getCurrentUserId();
        boolean hasAccess = teacherClassRepository.existsByClassIdAndTeacherId(classId, currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("User is not a teacher of this class");
        }

        List<TeacherClass> teacherClasses = teacherClassRepository.findByClassId(classId);

        return teacherClasses.stream()
                .map(teacherClass -> {
                    User teacher = userRepository.findById(teacherClass.getTeacherId())
                            .orElseThrow(() -> new ResourceNotFoundException("User", "id", teacherClass.getTeacherId()));

                    return GetClassTeachersResponse.builder()
                            .id(teacher.getId())
                            .email(teacher.getEmail())
                            .fullName(teacher.getFullName())
                            .addedAt(teacherClass.getAddedAt())
                            .isCreator(teacher.getId().equals(clazz.getCreatorId()))
                            .build();
                })
                .toList();
    }
}
