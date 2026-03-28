package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.*;
import graduation_project_be.application.usecases.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsecasesConfiguration {

    @Bean
    CreateClassUsecase createClassUsecase(
            ClassRepository classRepository,
            TeacherClassRepository teacherClassRepository,
            UserRepository userRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService) {
        return new CreateClassUsecase(classRepository, teacherClassRepository, userRepository,
                classEnrollmentRepository, passwordEncoder, currentUserService);
    }

    @Bean
    LoginUsecase authenticationUsecase(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokenIssuer) {
        return new LoginUsecase(
                userRepository,
                passwordEncoder,
                tokenIssuer);
    }

    @Bean
    TokenIssuer tokenIssuer(
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher) {
        return new TokenIssuer(jwtService, refreshTokenRepository, refreshTokenHasher);
    }

    @Bean
    GoogleLoginUsecase googleLoginUsecase(
            UserRepository userRepository,
            GoogleAuthService googleAuthService,
            TokenIssuer tokenIssuer) {
        return new GoogleLoginUsecase(userRepository, googleAuthService, tokenIssuer);
    }

    @Bean
    MicrosoftLoginUsecase microsoftLoginUsecase(
            UserRepository userRepository,
            MicrosoftAuthService microsoftAuthService,
            TokenIssuer tokenIssuer) {
        return new MicrosoftLoginUsecase(userRepository, microsoftAuthService, tokenIssuer);
    }

    @Bean
    GetCurrentUserUsecase getCurrentUserUsecase(
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new GetCurrentUserUsecase(userRepository, currentUserService);
    }

    @Bean
    RefreshUsecase refreshUsecase(
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            RefreshTokenHasher refreshTokenHasher,
            UserRepository userRepository) {
        return new RefreshUsecase(refreshTokenRepository, jwtService, refreshTokenHasher, userRepository);
    }

    @Bean
    LogoutUsecase logoutUsecase(
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            RefreshTokenHasher refreshTokenHasher) {
        return new LogoutUsecase(refreshTokenRepository, jwtService, refreshTokenHasher);
    }

    @Bean
    GetClassesUsecase getClassesUsecase(
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetClassesUsecase(classRepository, currentUserService);
    }

    @Bean
    GetStudentsInClassUsecase getStudentsInClassUsecase(
            ClassEnrollmentRepository classEnrollmentRepository,
            ClassRepository classRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new GetStudentsInClassUsecase(
                classEnrollmentRepository,
                classRepository,
                userRepository,
                currentUserService);
    }

    @Bean
    GetStudentExamUsecase getStudentExamUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService) {
        return new GetStudentExamUsecase(examRepository, classRepository, classEnrollmentRepository, currentUserService);
    }

    @Bean
    CreateExamUsecase createExamUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            ClassEnrollmentRepository classEnrollmentRepository,
            ExamSchemaService examSchemaService) {
        return new CreateExamUsecase(
                examRepository,
                classRepository,
                currentUserService,
                classEnrollmentRepository,
                examSchemaService);
    }

    @Bean
    GetClassDetailUsecase getClassDetailUsecase(
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetClassDetailUsecase(classRepository, currentUserService);
    }

    @Bean
    UpdateExamUsecase updateExamUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new UpdateExamUsecase(examRepository, classRepository, currentUserService);
    }

    @Bean
    GetTeacherExamDetailUsecase getTeacherExamDetailUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetTeacherExamDetailUsecase(examRepository, classRepository, currentUserService);
    }

    @Bean
    GetExamMonitorUsecase getExamMonitorUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            UserRepository userRepository,
            ExamViolationRepository examViolationRepository,
            CurrentUserService currentUserService) {
        return new GetExamMonitorUsecase(
                examRepository,
                classRepository,
                classEnrollmentRepository,
                userRepository,
                examViolationRepository,
                currentUserService);
    }

    // ===== NEW USECASES =====

    @Bean
    CreateExamQuestionUsecase createExamQuestionUsecase(
            ClassRepository classRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService,
            ExamSpecificationRepository examSpecificationRepository,
            GeminiService geminiService) {
        return new CreateExamQuestionUsecase(classRepository, examQuestionRepository, examRepository,
                currentUserService, examSpecificationRepository, geminiService);
    }

    @Bean
    GetExamQuestionsUsecase getExamQuestionsUsecase(
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService) {
        return new GetExamQuestionsUsecase(examQuestionRepository, examRepository,
                classRepository, classEnrollmentRepository, currentUserService);
    }

    @Bean
    GetStudentExamsUsecase getStudentExamsUsecase(
            ExamRepository examRepository,
            CurrentUserService currentUserService) {
        return new GetStudentExamsUsecase(examRepository, currentUserService);
    }

    @Bean
    ExecuteSqlUsecase executeSqlUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService) {
        return new ExecuteSqlUsecase(examRepository, classRepository, classEnrollmentRepository,
                currentUserService, examSchemaService, examSessionService);
    }

    @Bean
    SubmitExamUsecase submitExamUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamResultRepository examResultRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSessionService examSessionService,
            GradingQueueService gradingQueueService) {
        return new SubmitExamUsecase(
                examRepository, examQuestionRepository, examSubmissionRepository,
                examResultRepository, classEnrollmentRepository, currentUserService,
                examSessionService, gradingQueueService);
    }

    @Bean
    GradeExamUsecase gradeExamUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamResultRepository examResultRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService,
            GradingNotificationService gradingNotificationService,
            UserRepository userRepository,
            TestCaseRepository testCaseRepository,
            ObjectMapper objectMapper) {
        return new GradeExamUsecase(
                examRepository, examQuestionRepository, examSubmissionRepository,
                examResultRepository, examSpecificationRepository,
                examSchemaService, examSessionService, gradingNotificationService, userRepository, testCaseRepository, objectMapper);
    }

    @Bean
    RubricTestingUsecase rubricTestingUsecase(
            GeminiService geminiService,
            ExamSchemaService examSchemaService,
            GetExamQuestionsUsecase getExamQuestionsUsecase,
            GradeExamUsecase gradeExamUsecase,
            ObjectMapper objectMapper) {
        return new RubricTestingUsecase(
                geminiService, examSchemaService,
                getExamQuestionsUsecase, gradeExamUsecase, objectMapper);
    }

    @Bean
    GetExamResultsUsecase getExamResultsUsecase(
            ExamRepository examRepository,
            ExamResultRepository examResultRepository,
            UserRepository userRepository) {
        return new GetExamResultsUsecase(examRepository, examResultRepository, userRepository);
    }

    @Bean
    GetExamsByClassUsecase getExamsByClassUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetExamsByClassUsecase(examRepository, classRepository, currentUserService);
    }

    @Bean
    CreateSpecificationUsecase createSpecificationUsecase(
            ExamSpecificationRepository examSpecificationRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService) {
        return new CreateSpecificationUsecase(examSpecificationRepository, currentUserService, examSchemaService);
    }

    @Bean
    UpdateSpecificationUsecase updateSpecificationUsecase(
            ExamSpecificationRepository examSpecificationRepository,
            ExamSchemaService examSchemaService) {
        return new UpdateSpecificationUsecase(examSpecificationRepository, examSchemaService);
    }

    @Bean
    GetSpecificationsUsecase getSpecificationsUsecase(
            ExamSpecificationRepository examSpecificationRepository) {
        return new GetSpecificationsUsecase(examSpecificationRepository);
    }

    @Bean
    GetSpecificationByIdUsecase getSpecificationByIdUsecase(
            ExamSpecificationRepository examSpecificationRepository) {
        return new GetSpecificationByIdUsecase(examSpecificationRepository);
    }
    // ===== ANTI-CHEATING USECASES =====

    @Bean
    ReportViolationUsecase reportViolationUsecase(
            ExamViolationRepository examViolationRepository,
            ExamResultRepository examResultRepository,
            ExamRepository examRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ViolationNotificationService violationNotificationService,
            SubmitExamUsecase submitExamUsecase,
            ExamSessionService examSessionService) {
        return new ReportViolationUsecase(
                examViolationRepository, examResultRepository, examRepository,
                classEnrollmentRepository, currentUserService,
                violationNotificationService, submitExamUsecase,
                examSessionService);
    }

    @Bean
    GetViolationsUsecase getViolationsUsecase(
            ClassRepository classRepository,
            ExamViolationRepository examViolationRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService) {
        return new GetViolationsUsecase(
                classRepository, examViolationRepository, examRepository, currentUserService);
    }

    @Bean
    StartExamSessionUsecase startExamSessionUsecase(
            ExamRepository examRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSessionService examSessionService,
            ExamResultRepository examResultRepository) {
        return new StartExamSessionUsecase(
                examRepository, classEnrollmentRepository,
                currentUserService, examSessionService,
                examResultRepository);
    }

    @Bean
    GetExamTimeUsecase getExamTimeUsecase(
            ExamRepository examRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSessionService examSessionService) {
        return new GetExamTimeUsecase(
                examRepository, classEnrollmentRepository,
                currentUserService, examSessionService);
    }

    // ===== NOTIFICATION USECASES =====

    @Bean
    GetTeacherNotificationsUsecase getTeacherNotificationsUsecase(
            TeacherNotificationRepository teacherNotificationRepository,
            CurrentUserService currentUserService) {
        return new GetTeacherNotificationsUsecase(teacherNotificationRepository, currentUserService);
    }

    @Bean
    MarkNotificationReadUsecase markNotificationReadUsecase(
            TeacherNotificationRepository teacherNotificationRepository,
            CurrentUserService currentUserService) {
        return new MarkNotificationReadUsecase(teacherNotificationRepository, currentUserService);
    }

    @Bean
    GetUnreadNotificationCountUsecase getUnreadNotificationCountUsecase(
            TeacherNotificationRepository teacherNotificationRepository,
            CurrentUserService currentUserService) {
        return new GetUnreadNotificationCountUsecase(teacherNotificationRepository, currentUserService);
    }

    @Bean
    DeleteNotificationUsecase deleteNotificationUsecase(
            TeacherNotificationRepository teacherNotificationRepository,
            CurrentUserService currentUserService) {
        return new DeleteNotificationUsecase(teacherNotificationRepository, currentUserService);
    }

    // ===== SPECIFICATION USECASES =====

    @Bean
    SaveExamSpecificationUsecase saveExamSpecificationUsecase(
            ClassRepository classRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService) {
        return new SaveExamSpecificationUsecase(classRepository, examSpecificationRepository, examRepository,
                currentUserService, examSchemaService);
    }

    @Bean
    GetExamSpecificationUsecase getExamSpecificationUsecase(
            ClassRepository classRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ExamRepository examRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService) {
        return new GetExamSpecificationUsecase(examSpecificationRepository, examRepository,
                classRepository, classEnrollmentRepository, currentUserService);
    }

    @Bean
    GetTeacherExamSettingsUsecase getTeacherExamSettingsUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetTeacherExamSettingsUsecase(examRepository, classRepository, currentUserService);
    }

    @Bean
    UpdateTeacherExamSettingsUsecase updateTeacherExamSettingsUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new UpdateTeacherExamSettingsUsecase(examRepository, classRepository, currentUserService);
    }

    // ===== AI USECASES =====

    @Bean
    CreateExamQuestionsUsecase createExamQuestionsUsecase(
            ClassRepository classRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            ExamSpecificationRepository examSpecificationRepository,
            CurrentUserService currentUserService,
            GeminiService geminiService) {
        return new CreateExamQuestionsUsecase(classRepository, examQuestionRepository, examRepository,
                examSpecificationRepository, currentUserService, geminiService);
    }

        @Bean
    AddTeacherToClassUsecase addTeacherToClassUsecase(
            ClassRepository classRepository,
            TeacherClassRepository teacherClassRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new AddTeacherToClassUsecase(
                classRepository,
                teacherClassRepository,
                userRepository,
                currentUserService);
    }

    @Bean
    GetClassTeachersUsecase getClassTeachersUsecase(
            ClassRepository classRepository,
            TeacherClassRepository teacherClassRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new GetClassTeachersUsecase(
                classRepository,
                teacherClassRepository,
                userRepository,
                currentUserService);
    }

    @Bean
    RemoveTeacherFromClassUsecase removeTeacherFromClassUsecase(
            ClassRepository classRepository,
            TeacherClassRepository teacherClassRepository,
            CurrentUserService currentUserService) {
        return new RemoveTeacherFromClassUsecase(
                classRepository,
                teacherClassRepository,
                currentUserService);
    }

    // ===== LIBRARY USECASES =====

    @Bean
    ShareExamAsTemplateUsecase shareExamAsTemplateUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ExamTemplateRepository examTemplateRepository,
            ExamTemplateQuestionRepository examTemplateQuestionRepository,
            CurrentUserService currentUserService) {
        return new ShareExamAsTemplateUsecase(
                examRepository,
                examQuestionRepository,
                examSpecificationRepository,
                examTemplateRepository,
                examTemplateQuestionRepository,
                currentUserService);
    }

    @Bean
    GetExamTemplatesUsecase getExamTemplatesUsecase(
            ExamTemplateRepository examTemplateRepository,
            ExamTemplateQuestionRepository examTemplateQuestionRepository,
            UserRepository userRepository) {
        return new GetExamTemplatesUsecase(examTemplateRepository, examTemplateQuestionRepository, userRepository);
    }

    @Bean
    GetExamTemplateVersionsUsecase getExamTemplateVersionsUsecase(
            ExamTemplateRepository examTemplateRepository,
            ExamTemplateQuestionRepository examTemplateQuestionRepository,
            UserRepository userRepository) {
        return new GetExamTemplateVersionsUsecase(examTemplateRepository, examTemplateQuestionRepository,
                userRepository);
    }

    @Bean
    GetTeacherExamTemplateVersionsUsecase getTeacherExamTemplateVersionsUsecase(
            ExamRepository examRepository,
            ExamTemplateRepository examTemplateRepository,
            ExamTemplateQuestionRepository examTemplateQuestionRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            UserRepository userRepository) {
        return new GetTeacherExamTemplateVersionsUsecase(
                examRepository,
                examTemplateRepository,
                examTemplateQuestionRepository,
                classRepository,
                currentUserService,
                userRepository);
    }

    @Bean
    CloneExamTemplateUsecase cloneExamTemplateUsecase(
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            ExamTemplateRepository examTemplateRepository,
            ExamTemplateQuestionRepository examTemplateQuestionRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSpecificationRepository examSpecificationRepository,
            ExamSchemaService examSchemaService) {
        return new CloneExamTemplateUsecase(
                examTemplateRepository,
                examTemplateQuestionRepository,
                classRepository,
                classEnrollmentRepository, currentUserService,
                examSpecificationRepository,
                examRepository,
                examQuestionRepository,
                examSchemaService);
    }

    @Bean
    UpdateExamTemplateVisibilityUsecase updateExamTemplateVisibilityUsecase(
            ExamTemplateRepository examTemplateRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService) {
        return new UpdateExamTemplateVisibilityUsecase(
                examTemplateRepository,
                examRepository,
                currentUserService);
    }

    @Bean
    HideExamTemplateLineageUsecase hideExamTemplateLineageUsecase(
            ExamTemplateRepository examTemplateRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService) {
        return new HideExamTemplateLineageUsecase(
                examTemplateRepository,
                examRepository,
                currentUserService);
    }

}
