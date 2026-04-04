package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.TeacherClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.request.UpdateClassRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class UpdateClassUsecase {

    private static final String STUDENT_EMAIL_SUFFIX = "@student.hcmus.edu.vn";
    private static final String DEFAULT_STUDENT_PASSWORD = "Vlchinsu1234*";

    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;


    @Transactional
    public CreateClassResponse execute(UpdateClassRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        Class existingClass = classRepository.findById(request.classId());
        if (existingClass == null) {
            throw new RuntimeException("Class not found");
        }

        // Check permission: only creator or authorized teacher can update
        boolean hasAccess = classRepository.existsTeacherAccess(request.classId(), teacherId);

        if (!hasAccess) {
            throw new RuntimeException("You are not authorized to update this class");
        }

        
        existingClass.setClassCode(request.classCode());
        existingClass.setSemester(request.semester());
        
        Class savedClass = classRepository.save(existingClass);

        // Sync students:
        // 1. Delete all existing enrollments
        classEnrollmentRepository.deleteByClassId(savedClass.getId());

        // 2. Add new enrollments
        List<ClassEnrollment> enrollments = new ArrayList<>();

        for (UpdateClassRequest.StudentInfo studentInfo : request.students()) {
            String email = studentInfo.studentId() + STUDENT_EMAIL_SUFFIX;
            
            Optional<User> existingUser = userRepository.findByEmail(email);
            User student;
            
            if (existingUser.isPresent()) {
                student = existingUser.get();
                student.setFullName(studentInfo.fullName());
                userRepository.save(student);
            } else {
                student = User.builder()
                        .email(email)
                        .fullName(studentInfo.fullName())
                        .password(passwordEncoder.encode(DEFAULT_STUDENT_PASSWORD))
                        .role(Role.STUDENT)
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .build();
                student = userRepository.save(student);
            }

            ClassEnrollment enrollment = ClassEnrollment.builder()
                    .classId(savedClass.getId())
                    .studentId(student.getId())
                    .joinedAt(LocalDateTime.now())
                    .build();
            enrollments.add(enrollment);
        }

        classEnrollmentRepository.saveAll(enrollments);

        return CreateClassResponse.fromModel(savedClass);
    }
}
