package graduation_project_be.application.usecases;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.PasswordEncoder;
import graduation_project_be.application.usecases.request.UpdateClassRequest;
import graduation_project_be.application.usecases.response.CreateClassResponse;
import graduation_project_be.application.usecases.support.ClassInputValidator;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        ClassInputValidator.validateClassDetails(request.classCode(), request.semester());
        
        existingClass.setClassCode(request.classCode().trim());
        existingClass.setSemester(request.semester().trim());
        
        Class savedClass = classRepository.save(existingClass);

        // Sync students:
        // 1. Delete all existing enrollments
        classEnrollmentRepository.deleteByClassId(savedClass.getId());

        // 2. Add new enrollments
        List<ClassEnrollment> enrollments = new ArrayList<>();

        Map<String, UpdateClassRequest.StudentInfo> normalizedStudents = normalizeStudents(request.students());

        for (Map.Entry<String, UpdateClassRequest.StudentInfo> entry : normalizedStudents.entrySet()) {
            String studentCode = entry.getKey();
            UpdateClassRequest.StudentInfo studentInfo = entry.getValue();
            String email = studentCode + STUDENT_EMAIL_SUFFIX;
            
            Optional<User> existingUser = userRepository.findByEmail(email);
            User student;
            
            if (existingUser.isPresent()) {
                student = existingUser.get();
                if (studentInfo.fullName() != null && !studentInfo.fullName().isBlank()) {
                    student.setFullName(studentInfo.fullName().trim());
                    userRepository.save(student);
                }
            } else {
                student = User.builder()
                        .email(email)
                        .fullName(resolveDisplayName(studentInfo.fullName(), studentCode))
                        .password(passwordEncoder.encode(DEFAULT_STUDENT_PASSWORD))
                        .role(Role.STUDENT)
                        .isActive(true)
                        .createdAt(TimeUtils.now())
                        .build();
                student = userRepository.save(student);
            }

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

    private Map<String, UpdateClassRequest.StudentInfo> normalizeStudents(
            List<UpdateClassRequest.StudentInfo> students) {
        Map<String, UpdateClassRequest.StudentInfo> normalized = new LinkedHashMap<>();
        if (students == null) {
            return normalized;
        }

        for (UpdateClassRequest.StudentInfo studentInfo : students) {
            if (studentInfo == null || studentInfo.studentId() == null || studentInfo.studentId().isBlank()) {
                continue;
            }
            String studentCode = studentInfo.studentId().trim();
            if (!ClassInputValidator.isValidStudentCode(studentCode)) {
                throw new BadRequestException("MSSV phải gồm đúng 8 chữ số: " + studentCode);
            }
            if (normalized.containsKey(studentCode)) {
                throw new BadRequestException("Danh sách sinh viên có MSSV bị trùng: " + studentCode);
            }
            normalized.put(studentCode, studentInfo);
        }
        return normalized;
    }

    private String resolveDisplayName(String fullName, String studentCode) {
        return fullName == null || fullName.isBlank() ? studentCode : fullName.trim();
    }
}
