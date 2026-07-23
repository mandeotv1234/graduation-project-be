package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.TeacherClassNotificationService;
import graduation_project_be.application.usecases.request.AddTeacherToClassRequest;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class AddTeacherToClassUsecase {

    private static final String FIT_TEACHER_EMAIL_DOMAIN = "@fit.hcmus.edu.vn";
    private static final String VNG_TEST_TEACHER_EMAIL = "manh@vng.com.vn";

    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final TeacherClassNotificationService teacherClassNotificationService;

    @Transactional
    public void execute(AddTeacherToClassRequest request) {
        Class clazz = classRepository.findById(request.classId());

        User currentUser = currentUserService.getCurrentUser();
        boolean hasAccess = classRepository.existsTeacherAccess(request.classId(), currentUser.getId());
        if (!hasAccess) {
            throw new UnauthorizedException("User is not a teacher of this class");
        }

        String normalizedEmail = request.email() == null ? "" : request.email().trim().toLowerCase();
        if (normalizedEmail.isBlank()) {
            throw new BadRequestException("Teacher email is required");
        }
        if (!hasAllowedTeacherEmailDomain(normalizedEmail)) {
            throw new BadRequestException("Teacher email is not allowed");
        }

        User teacher = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", normalizedEmail));

        if (teacher.getRole() != Role.TEACHER) {
            throw new BadRequestException("User is not a teacher");
        }

        if (!Boolean.TRUE.equals(teacher.getIsActive())) {
            throw new BadRequestException("Teacher is inactive");
        }

        boolean alreadyExists = classRepository.existsTeacherAccess(clazz.getId(), teacher.getId());
        if (alreadyExists) {
            throw new ConflictException("TeacherClass", "classId-teacherId", clazz.getId() + "-" + teacher.getId());
        }

        TeacherClass teacherClass = TeacherClass.builder()
                .classId(clazz.getId())
                .teacherId(teacher.getId())
                .addedAt(TimeUtils.now())
                .build();

        classRepository.saveTeacherAssociation(teacherClass);
        teacherClassNotificationService.notifyTeacherAdded(clazz, teacher, currentUser);
    }

    private boolean hasAllowedTeacherEmailDomain(String email) {
        int atIndex = email.lastIndexOf('@');
        boolean isFitTeacherEmail = atIndex > 0
                && email.indexOf('@') == atIndex
                && FIT_TEACHER_EMAIL_DOMAIN.equals(email.substring(atIndex));
        return isFitTeacherEmail || VNG_TEST_TEACHER_EMAIL.equals(email);
    }
}
