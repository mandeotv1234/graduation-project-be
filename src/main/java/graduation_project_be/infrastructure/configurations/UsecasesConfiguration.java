package graduation_project_be.infrastructure.configurations;

import graduation_project_be.application.usecases.DeleteExamUsecase;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.*;
import graduation_project_be.application.usecases.*;
import graduation_project_be.application.usecases.grading.*;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxCatalog;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxEngine;
import graduation_project_be.infrastructure.services.JSqlParserSelectQueryStructureAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@Configuration
public class UsecasesConfiguration {

    @Bean
    UpdateClassUsecase updateClassUsecase(
            ClassRepository classRepository,
            UserRepository userRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService) {
        return new UpdateClassUsecase(classRepository, userRepository, classEnrollmentRepository,
                passwordEncoder, currentUserService);
    }



    @Bean
    CreateClassUsecase createClassUsecase(
            ClassRepository classRepository,
            UserRepository userRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService) {
        return new CreateClassUsecase(classRepository, userRepository,
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
    SoftDeleteClassUsecase softDeleteClassUsecase(
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new SoftDeleteClassUsecase(classRepository, currentUserService);
    }

    @Bean
    RestoreClassUsecase restoreClassUsecase(
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new RestoreClassUsecase(classRepository, currentUserService);
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
    GetStudentProgressInClassUsecase getStudentProgressInClassUsecase(
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            ExamRepository examRepository,
            ExamResultRepository examResultRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new GetStudentProgressInClassUsecase(
                classRepository,
                classEnrollmentRepository,
                examRepository,
                examResultRepository,
                userRepository,
                currentUserService);
    }

    @Bean
    GetStudentExamUsecase getStudentExamUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService,
            ExamResultRepository examResultRepository) {
        return new GetStudentExamUsecase(
                examRepository,
                classRepository,
                classEnrollmentRepository,
                currentUserService,
                examSchemaService,
                examResultRepository);
    }

    @Bean
    CreateExamUsecase createExamUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            ClassEnrollmentRepository classEnrollmentRepository,
            ExamSchemaService examSchemaService,
            ExamSpecificationRepository examSpecificationRepository) {
        return new CreateExamUsecase(
                examRepository,
                classRepository,
                currentUserService,
                classEnrollmentRepository,
                examSchemaService,
                examSpecificationRepository);
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
            CurrentUserService currentUserService,
            ExamSpecificationRepository examSpecificationRepository) {
        return new UpdateExamUsecase(
                examRepository,
                classRepository,
                currentUserService,
                examSpecificationRepository);
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
            CurrentUserService currentUserService,
            ExamSessionService examSessionService,
            ExamResultRepository examResultRepository,
            ExamDraftRepository examDraftRepository) {
        return new GetExamMonitorUsecase(
                examRepository,
                classRepository,
                classEnrollmentRepository,
                userRepository,
                examViolationRepository,
                currentUserService,
                examSessionService,
                examResultRepository,
                examDraftRepository);
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
            ClassStudentBanRepository classStudentBanRepository,
            CurrentUserService currentUserService) {
        return new GetStudentExamsUsecase(examRepository, classStudentBanRepository, currentUserService);
    }

    @Bean
    GetStudentResultsUsecase getStudentResultsUsecase(
            ExamRepository examRepository,
            ExamResultRepository examResultRepository,
            CurrentUserService currentUserService) {
        return new GetStudentResultsUsecase(examRepository, examResultRepository, currentUserService);
    }

    @Bean
    GetMyResultDetailUsecase getMyResultDetailUsecase(
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper) {
        return new GetMyResultDetailUsecase(examResultRepository, examSubmissionRepository, examQuestionRepository, examRepository, userRepository, currentUserService, objectMapper);
    }

    @Bean
    ClearExamSchemaUsecase clearExamSchemaUsecase(
            ExamRepository examRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService,
            ExamResultRepository examResultRepository) {
        return new ClearExamSchemaUsecase(examRepository, classEnrollmentRepository,
                currentUserService, examSchemaService, examSessionService, examResultRepository);
    }

    @Bean
    ExecuteSqlUsecase executeSqlUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService,
            ExamResultRepository examResultRepository) {
        return new ExecuteSqlUsecase(examRepository, classRepository, classEnrollmentRepository,
                currentUserService, examSchemaService, examSessionService, examResultRepository);
    }

    @Bean
    SubmitExamUsecase submitExamUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamResultRepository examResultRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            ClassRepository classRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService,
            ExamSessionService examSessionService,
            GradingQueueService gradingQueueService,
            ExamDraftRepository examDraftRepository,
            TeacherNotificationRepository teacherNotificationRepository,
            SimpMessagingTemplate simpMessagingTemplate,
            HeartbeatService heartbeatService) {
        return new SubmitExamUsecase(
                examRepository, examQuestionRepository, examSubmissionRepository,
                examResultRepository, classEnrollmentRepository, classRepository, userRepository, currentUserService,
                examSessionService, gradingQueueService, examDraftRepository, teacherNotificationRepository,
                simpMessagingTemplate, heartbeatService);
    }

    @Bean
    GradingSupport gradingSupport(
            ExamSchemaService examSchemaService,
            ObjectMapper objectMapper,
            TestCaseRepository testCaseRepository) {
        return new GradingSupport(examSchemaService, objectMapper, testCaseRepository);
    }

    @Bean
    SelectQueryStructureAnalyzer selectQueryStructureAnalyzer() {
        return new JSqlParserSelectQueryStructureAnalyzer();
    }

    @Bean
    WhiteboxCatalog whiteboxCatalog() {
        return new WhiteboxCatalog();
    }

    @Bean
    WhiteboxEngine whiteboxEngine(
            SelectQueryStructureAnalyzer selectQueryStructureAnalyzer,
            WhiteboxCatalog whiteboxCatalog) {
        return new WhiteboxEngine(selectQueryStructureAnalyzer, whiteboxCatalog);
    }

    @Bean
    WhiteboxCatalogUsecase whiteboxCatalogUsecase(WhiteboxCatalog whiteboxCatalog) {
        return new WhiteboxCatalogUsecase(whiteboxCatalog);
    }

    @Bean
    WhiteboxValidateUsecase whiteboxValidateUsecase(WhiteboxEngine whiteboxEngine) {
        return new WhiteboxValidateUsecase(whiteboxEngine);
    }

    @Bean
    CreateTableQuestionGrader createTableQuestionGrader(
            ExamSchemaService examSchemaService,
            ObjectMapper objectMapper,
            GradingSupport gradingSupport) {
        return new CreateTableQuestionGrader(examSchemaService, objectMapper, gradingSupport);
    }

    @Bean
    InsertDataQuestionGrader insertDataQuestionGrader(
            ExamSchemaService examSchemaService,
            ObjectMapper objectMapper,
            GradingSupport gradingSupport) {
        return new InsertDataQuestionGrader(examSchemaService, objectMapper, gradingSupport);
    }

    @Bean
    SelectQuestionGrader selectQuestionGrader(
            ExamSchemaService examSchemaService,
            ObjectMapper objectMapper,
            GradingSupport gradingSupport) {
        return new SelectQuestionGrader(examSchemaService, objectMapper, gradingSupport);
    }

    @Bean
    RoutineQuestionGrader routineQuestionGrader(
            ExamSchemaService examSchemaService,
            ObjectMapper objectMapper,
            TestCaseRepository testCaseRepository,
            GradingSupport gradingSupport) {
        return new RoutineQuestionGrader(examSchemaService, objectMapper, testCaseRepository, gradingSupport);
    }

    @Bean
    TriggerQuestionGrader triggerQuestionGrader(
            ExamSchemaService examSchemaService,
            TestCaseRepository testCaseRepository,
            GradingSupport gradingSupport,
            RoutineQuestionGrader routineQuestionGrader) {
        return new TriggerQuestionGrader(examSchemaService, testCaseRepository, gradingSupport, routineQuestionGrader);
    }

    @Bean
    GradeExamUsecase gradeExamUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamResultRepository examResultRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ClassRepository classRepository,
            ExamSchemaService examSchemaService,
            ExamSessionService examSessionService,
            GradingNotificationService gradingNotificationService,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            GradingSupport gradingSupport,
            CreateTableQuestionGrader createTableQuestionGrader,
            InsertDataQuestionGrader insertDataQuestionGrader,
            SelectQuestionGrader selectQuestionGrader,
            RoutineQuestionGrader routineQuestionGrader,
            TriggerQuestionGrader triggerQuestionGrader,
            WhiteboxEngine whiteboxEngine) {
        return new GradeExamUsecase(
                examRepository, examQuestionRepository, examSubmissionRepository,
                examResultRepository, examSpecificationRepository, classRepository,
                examSchemaService, examSessionService, gradingNotificationService, userRepository,
                objectMapper, gradingSupport, createTableQuestionGrader, insertDataQuestionGrader,
                selectQuestionGrader, routineQuestionGrader, triggerQuestionGrader, whiteboxEngine);
    }

    @Bean
    RubricTestingUsecase rubricTestingUsecase(
            GeminiService geminiService,
            ExamSchemaService examSchemaService,
            ExamRepository examRepository,
            ExamSpecificationRepository examSpecificationRepository,
            GetExamQuestionsUsecase getExamQuestionsUsecase,
            InsertDataQuestionGrader insertDataQuestionGrader,
            ObjectMapper objectMapper,
            SelectQuestionGrader selectQuestionGrader) {
        return new RubricTestingUsecase(
                geminiService, examSchemaService,
                examRepository, examSpecificationRepository,
                getExamQuestionsUsecase, insertDataQuestionGrader, objectMapper,
                selectQuestionGrader);
    }

    @Bean
    GetExamResultsUsecase getExamResultsUsecase(
            ExamRepository examRepository,
            ExamResultRepository examResultRepository,
            UserRepository userRepository) {
        return new GetExamResultsUsecase(examRepository, examResultRepository, userRepository);
    }

    @Bean
    GetExamStatisticsUsecase getExamStatisticsUsecase(
            ExamRepository examRepository,
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamViolationRepository examViolationRepository,
            ExamQuestionRepository examQuestionRepository,
            UserRepository userRepository) {
        return new GetExamStatisticsUsecase(
                examRepository,
                examResultRepository,
                examSubmissionRepository,
                examViolationRepository,
                examQuestionRepository,
                userRepository);
    }

    @Bean
    GetExamResultDetailUsecase getExamResultDetailUsecase(
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamQuestionRepository examQuestionRepository,
            UserRepository userRepository,
            TestCaseRepository testCaseRepository,
            ObjectMapper objectMapper) {
        return new GetExamResultDetailUsecase(examResultRepository, examSubmissionRepository, examQuestionRepository,
                userRepository, testCaseRepository, objectMapper);
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
        return new CreateSpecificationUsecase(
                examSpecificationRepository,
                currentUserService,
                examSchemaService);
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
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            ViolationNotificationService violationNotificationService,
            SubmitExamUsecase submitExamUsecase,
            ExamDraftRepository examDraftRepository,
            UserRepository userRepository) {
        return new ReportViolationUsecase(
                examViolationRepository, examResultRepository, examRepository,
                classEnrollmentRepository, classRepository, currentUserService,
                violationNotificationService, submitExamUsecase, examDraftRepository,
                userRepository);
    }

    @Bean
    RecordHeartbeatUsecase recordHeartbeatUsecase(
            HeartbeatService heartbeatService,
            ExamRepository examRepository,
            CurrentUserService currentUserService,
            ReportViolationUsecase reportViolationUsecase) {
        return new RecordHeartbeatUsecase(
                heartbeatService, examRepository, currentUserService, reportViolationUsecase);
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
            ExamResultRepository examResultRepository,
            ExamSpecificationRepository examSpecificationRepository,
            ExamSchemaService examSchemaService,
            DeviceConflictStore deviceConflictStore,
            DeviceConflictNotificationService deviceConflictNotificationService,
            UserRepository userRepository,
            ClassRepository classRepository,
            ExamDraftRepository examDraftRepository,
            SimpMessagingTemplate simpMessagingTemplate,
            ClassStudentBanRepository classStudentBanRepository) {
        return new StartExamSessionUsecase(
                examRepository, classEnrollmentRepository,
                currentUserService, examSessionService,
                examResultRepository,
                examSpecificationRepository,
                examSchemaService,
                deviceConflictStore,
                deviceConflictNotificationService,
                userRepository,
                classRepository,
                examDraftRepository,
                simpMessagingTemplate,
                classStudentBanRepository);
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
            CurrentUserService currentUserService,
            ExamSpecificationRepository examSpecificationRepository) {
        return new UpdateTeacherExamSettingsUsecase(
                examRepository,
                classRepository,
                currentUserService,
                examSpecificationRepository);
    }

    // ===== AI USECASES =====

    @Bean
    CreateExamQuestionsUsecase createExamQuestionsUsecase(
            ClassRepository classRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            ExamSpecificationRepository examSpecificationRepository,
            CurrentUserService currentUserService,
            GeminiService geminiService,
            graduation_project_be.infrastructure.services.RubricToTestCaseTransformer rubricTransformer,
            graduation_project_be.infrastructure.services.ExpectedValueDeriver expectedValueDeriver) {
        return new CreateExamQuestionsUsecase(classRepository, examQuestionRepository, examRepository,
                examSpecificationRepository, currentUserService, geminiService,
                rubricTransformer, expectedValueDeriver);
    }

        @Bean
    AddTeacherToClassUsecase addTeacherToClassUsecase(
            ClassRepository classRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new AddTeacherToClassUsecase(
                classRepository,
                userRepository,
                currentUserService);
    }

    @Bean
    GetClassTeachersUsecase getClassTeachersUsecase(
            ClassRepository classRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new GetClassTeachersUsecase(
                classRepository,
                userRepository,
                currentUserService);
    }

    @Bean
    RemoveTeacherFromClassUsecase removeTeacherFromClassUsecase(
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new RemoveTeacherFromClassUsecase(
                classRepository,
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

    @Bean
    UpdateExamQuestionUsecase updateExamQuestionUsecase(
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new UpdateExamQuestionUsecase(examQuestionRepository, examRepository, classRepository, currentUserService);
    }

    @Bean
    DeleteExamQuestionUsecase deleteExamQuestionUsecase(
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new DeleteExamQuestionUsecase(examQuestionRepository, examRepository, classRepository, currentUserService);
    }

    @Bean
    DeleteExamUsecase deleteExamUsecase(ExamRepository examRepository, CurrentUserService currentUserService) {
        return new DeleteExamUsecase(examRepository, currentUserService);
    }

    // ===== EXAM DRAFT USECASES =====

    @Bean
    SaveExamDraftUsecase saveExamDraftUsecase(
            ExamRepository examRepository,
            ExamDraftRepository examDraftRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService) {
        return new SaveExamDraftUsecase(examRepository, examDraftRepository, classEnrollmentRepository, currentUserService);
    }

    @Bean
    GetExamDraftUsecase getExamDraftUsecase(
            ExamRepository examRepository,
            ExamDraftRepository examDraftRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService) {
        return new GetExamDraftUsecase(examRepository, examDraftRepository, classEnrollmentRepository, currentUserService);
    }

    // ===== REGRADE USECASES =====

    @Bean
    RegradeExamUsecase regradeExamUsecase(
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            GradingQueueService gradingQueueService) {
        return new RegradeExamUsecase(examResultRepository, examSubmissionRepository, gradingQueueService);
    }

    @Bean
    RegradeAllExamUsecase regradeAllExamUsecase(
            ExamRepository examRepository,
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            GradingQueueService gradingQueueService) {
        return new RegradeAllExamUsecase(examRepository, examResultRepository, examSubmissionRepository, gradingQueueService);
    }

    // ===== MANUAL OVERRIDE USECASES =====

    @Bean
    OverrideSubmissionScoreUsecase overrideSubmissionScoreUsecase(
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamQuestionRepository examQuestionRepository,
            CurrentUserService currentUserService) {
        return new OverrideSubmissionScoreUsecase(
                examResultRepository,
                examSubmissionRepository,
                examQuestionRepository,
                currentUserService);
    }

    // ===== PDF DOWNLOAD USECASE =====

    @Bean
    DownloadExamPdfUsecase downloadExamPdfUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            PdfStorageService pdfStorageService) {
        return new DownloadExamPdfUsecase(
                examRepository,
                classRepository,
                classEnrollmentRepository,
                currentUserService,
                pdfStorageService);
    }

    // ===== DEVICE CONFLICT USECASES =====

    @Bean
    ApproveDeviceConflictUsecase approveDeviceConflictUsecase(
            DeviceConflictStore deviceConflictStore,
            ExamSessionService examSessionService,
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            DeviceConflictNotificationService deviceConflictNotificationService) {
        return new ApproveDeviceConflictUsecase(
                deviceConflictStore, examSessionService,
                examRepository, classRepository,
                currentUserService, deviceConflictNotificationService);
    }

    @Bean
    RejectDeviceConflictUsecase rejectDeviceConflictUsecase(
            DeviceConflictStore deviceConflictStore,
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            DeviceConflictNotificationService deviceConflictNotificationService) {
        return new RejectDeviceConflictUsecase(
                deviceConflictStore, examRepository,
                classRepository, currentUserService,
                deviceConflictNotificationService);
    }

    // ===== FEEDBACK USECASES =====

    @Bean
    SubmitFeedbackUsecase submitFeedbackUsecase(FeedbackRepository feedbackRepository) {
        return new SubmitFeedbackUsecase(feedbackRepository);
    }

    @Bean
    GetFeedbacksUsecase getFeedbacksUsecase(
            FeedbackRepository feedbackRepository,
            UserRepository userRepository) {
        return new GetFeedbacksUsecase(feedbackRepository, userRepository);
    }

    // ===== ADMIN USER USECASES =====

    @Bean
    GetUsersUsecase getUsersUsecase(UserRepository userRepository) {
        return new GetUsersUsecase(userRepository);
    }

    @Bean
    UpdateUserRoleUsecase updateUserRoleUsecase(UserRepository userRepository) {
        return new UpdateUserRoleUsecase(userRepository);
    }

    @Bean
    ExtractQuestionsFromPdfUsecase extractQuestionsFromPdfUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ExamSpecificationRepository examSpecificationRepository,
            PdfStorageService pdfStorageService,
            CurrentUserService currentUserService,
            GeminiService geminiService) {
        return new ExtractQuestionsFromPdfUsecase(examRepository, classRepository, examSpecificationRepository,
                pdfStorageService, currentUserService, geminiService);
    }

    @Bean
    RemindStudentUsecase remindStudentUsecase(
            ExamRepository examRepository,
            CurrentUserService currentUserService,
            ViolationNotificationService violationNotificationService,
            ClassRepository classRepository) {
        return new RemindStudentUsecase(examRepository, currentUserService, violationNotificationService, classRepository);
    }

    @Bean
    ForceSubmitExamUsecase forceSubmitExamUsecase(
            ExamRepository examRepository,
            CurrentUserService currentUserService,
            SubmitExamUsecase submitExamUsecase,
            ViolationNotificationService violationNotificationService,
            ExamSessionService examSessionService,
            ExamDraftRepository examDraftRepository,
            ClassRepository classRepository) {
        return new ForceSubmitExamUsecase(examRepository, currentUserService, submitExamUsecase, violationNotificationService, examSessionService, examDraftRepository, classRepository);
    }

    // ===== ENTITY DESCRIPTION AI USECASES =====

    @Bean
    GenerateSpecEntityDescriptionUsecase generateSpecEntityDescriptionUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            SpecEntityRepository specEntityRepository,
            CurrentUserService currentUserService,
            GeminiService geminiService) {
        return new GenerateSpecEntityDescriptionUsecase(
                examRepository, classRepository, specEntityRepository, currentUserService, geminiService);
    }

    @Bean
    UpdateSpecEntityDescriptionUsecase updateSpecEntityDescriptionUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            SpecEntityRepository specEntityRepository,
            CurrentUserService currentUserService) {
        return new UpdateSpecEntityDescriptionUsecase(
                examRepository, classRepository, specEntityRepository, currentUserService);
    }

    // ===== EXPORT PDF USECASES =====

    @Bean
    ExportExamPdfUsecase exportExamPdfUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSpecificationRepository examSpecificationRepository,
            SpecEntityRepository specEntityRepository,
            CurrentUserService currentUserService,
            GeminiService geminiService,
            PdfRenderService pdfRenderService,
            @Qualifier("geminiExecutor") ExecutorService geminiExecutor) {
        return new ExportExamPdfUsecase(
                examRepository, classRepository, examQuestionRepository,
                examSpecificationRepository, specEntityRepository, currentUserService,
                geminiService, pdfRenderService, geminiExecutor);
    }

    // ===== TEACHER REVIEW SCHEMA USECASES =====

    @Bean
    TeacherExecuteSqlOnResultUsecase teacherExecuteSqlOnResultUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService) {
        return new TeacherExecuteSqlOnResultUsecase(
                examRepository,
                classRepository,
                examResultRepository,
                examSubmissionRepository,
                currentUserService,
                examSchemaService);
    }

    @Bean
    TeacherResetResultSchemaUsecase teacherResetResultSchemaUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            ExamResultRepository examResultRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamSpecificationRepository examSpecificationRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService) {
        return new TeacherResetResultSchemaUsecase(
                examRepository,
                classRepository,
                examResultRepository,
                examSubmissionRepository,
                examSpecificationRepository,
                currentUserService,
                examSchemaService);
    }

    @Bean
    DropAllExamSchemasUsecase dropAllExamSchemasUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService) {
        return new DropAllExamSchemasUsecase(
                examRepository,
                classRepository,
                currentUserService,
                examSchemaService);
    }

    // ===== BAN USECASES =====

    @Bean
    BanStudentUsecase banStudentUsecase(
            ClassRepository classRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            ClassStudentBanRepository classStudentBanRepository,
            CurrentUserService currentUserService) {
        return new BanStudentUsecase(
                classRepository,
                classEnrollmentRepository,
                classStudentBanRepository,
                currentUserService);
    }

    @Bean
    UnbanStudentUsecase unbanStudentUsecase(
            ClassRepository classRepository,
            ClassStudentBanRepository classStudentBanRepository,
            CurrentUserService currentUserService) {
        return new UnbanStudentUsecase(
                classRepository,
                classStudentBanRepository,
                currentUserService);
    }

    @Bean
    GetClassBansUsecase getClassBansUsecase(
            ClassRepository classRepository,
            ClassStudentBanRepository classStudentBanRepository,
            UserRepository userRepository,
            CurrentUserService currentUserService) {
        return new GetClassBansUsecase(
                classRepository,
                classStudentBanRepository,
                userRepository,
                currentUserService);
    }

}

