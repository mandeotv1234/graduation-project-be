package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.TeacherClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.AddTeacherToClassRequest;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AddTeacherToClassUsecase {

    private final ClassRepository classRepository;
    private final TeacherClassRepository teacherClassRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public void execute(AddTeacherToClassRequest request) {
        Class clazz = classRepository.findById(request.classId());

        Long currentUserId = currentUserService.getCurrentUserId();
        boolean hasAccess = teacherClassRepository.existsByClassIdAndTeacherId(request.classId(), currentUserId);
        if (!hasAccess) {
            throw new UnauthorizedException("User is not a teacher of this class");
        }

        String normalizedEmail = request.email() == null ? "" : request.email().trim().toLowerCase();
        if (normalizedEmail.isBlank()) {
            throw new BadRequestException("Teacher email is required");
        }

        User teacher = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", normalizedEmail));

        if (teacher.getRole() != Role.TEACHER) {
            throw new BadRequestException("User is not a teacher");
        }

        if (!Boolean.TRUE.equals(teacher.getIsActive())) {
            throw new BadRequestException("Teacher is inactive");
        }

        boolean alreadyExists = teacherClassRepository.existsByClassIdAndTeacherId(clazz.getId(), teacher.getId());
        if (alreadyExists) {
            throw new ConflictException("TeacherClass", "classId-teacherId", clazz.getId() + "-" + teacher.getId());
        }

        TeacherClass teacherClass = TeacherClass.builder()
                .classId(clazz.getId())
                .teacherId(teacher.getId())
                .addedAt(LocalDateTime.now())
                .build();

        teacherClassRepository.save(teacherClass);
    }
}
