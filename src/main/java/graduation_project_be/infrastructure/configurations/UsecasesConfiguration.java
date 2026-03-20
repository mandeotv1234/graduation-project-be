package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.*;
import graduation_project_be.application.usecases.*;

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
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService) {
        return new GetStudentExamUsecase(examRepository, classEnrollmentRepository, currentUserService);
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

    // ===== NEW USECASES =====

    @Bean
    CreateExamQuestionUsecase createExamQuestionUsecase(
            ClassRepository classRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService) {
        return new CreateExamQuestionUsecase(classRepository, examQuestionRepository, examRepository,
                currentUserService);
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
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService) {
        return new ExecuteSqlUsecase(examRepository, classEnrollmentRepository,
                currentUserService, examSchemaService, examSessionService);
    }

    @Bean
    SubmitExamUsecase submitExamUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamResultRepository examResultRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService) {
        return new SubmitExamUsecase(
                examRepository, examQuestionRepository, examSubmissionRepository,
                examResultRepository, examSpecificationRepository, classEnrollmentRepository, currentUserService,
                examSchemaService, examSessionService);
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
            CurrentUserService currentUserService) {
        return new SaveExamSpecificationUsecase(classRepository, examSpecificationRepository, examRepository,
                currentUserService);
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

}
