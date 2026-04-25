package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.request.CreateClassRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class CreateClassUsecase {

    private static final String STUDENT_EMAIL_SUFFIX = "@student.hcmus.edu.vn";
    private static final String DEFAULT_STUDENT_PASSWORD = "Vlchinsu1234*";

    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;

    @Transactional
    public CreateClassResponse execute(CreateClassRequest request) {
        Long teacherId = currentUserService.getCurrentUserId();

        Class newClass = Class.builder()
                .classCode(request.classCode())
                .semester(request.semester())
                .creatorId(teacherId)
                .createdAt(TimeUtils.now())
                .build();
        
        Class savedClass = classRepository.save(newClass);

        classRepository.saveTeacherAssociation(TeacherClass.builder()
                .classId(savedClass.getId())
                .teacherId(teacherId)
                .addedAt(TimeUtils.now())
                .build());

        List<User> studentsToSave = new ArrayList<>();
        List<ClassEnrollment> enrollments = new ArrayList<>();

        for (CreateClassRequest.StudentInfo studentInfo : request.students()) {
            String email = studentInfo.studentId() + STUDENT_EMAIL_SUFFIX;
            
            Optional<User> existingUser = userRepository.findByEmail(email);
            User student;
            
            if (existingUser.isPresent()) {
                student = existingUser.get();
            } else {
                student = User.builder()
                        .email(email)
                        .fullName(studentInfo.fullName())
                        .password(passwordEncoder.encode(DEFAULT_STUDENT_PASSWORD))
                        .role(Role.STUDENT)
                        .isActive(true)
                        .createdAt(TimeUtils.now())
                        .build();
                studentsToSave.add(student);
            }
        }

        userRepository.saveAll(studentsToSave);

        
        List<User> allStudents = new ArrayList<>();
        for (CreateClassRequest.StudentInfo studentInfo : request.students()) {
             String email = studentInfo.studentId() + STUDENT_EMAIL_SUFFIX;
             Optional<User> user = userRepository.findByEmail(email);
             user.ifPresent(allStudents::add);
        }

        for (User student : allStudents) {
            ClassEnrollment enrollment = ClassEnrollment.builder()
                    .classId(savedClass.getId())
                    .studentId(student.getId())
                    .joinedAt(TimeUtils.now())
                    .build();
            enrollments.add(enrollment);
        }

        classEnrollmentRepository.saveAll(enrollments);

        return CreateClassResponse.fromModel(savedClass);
    }
}
