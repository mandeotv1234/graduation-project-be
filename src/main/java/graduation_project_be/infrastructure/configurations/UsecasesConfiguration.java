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
            UserRepository userRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService) {
        return new CreateClassUsecase(classRepository, userRepository, classEnrollmentRepository, passwordEncoder,
                currentUserService);
    }

    @Bean
    LoginUsecase authenticationUsecase(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher) {
        return new LoginUsecase(
                userRepository,
                passwordEncoder,
                jwtService,
                refreshTokenRepository,
                refreshTokenHasher);
    }

    @Bean
    RefreshUsecase refreshUsecase(
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            RefreshTokenHasher refreshTokenHasher) {
        return new RefreshUsecase(refreshTokenRepository, jwtService, refreshTokenHasher);
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
            ExamQuestionRepository examQuestionRepository,
            ExamRepository examRepository,
            CurrentUserService currentUserService) {
        return new CreateExamQuestionUsecase(examQuestionRepository, examRepository, currentUserService);
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
            ExamSchemaService examSchemaService) {
        return new ExecuteSqlUsecase(examRepository, classEnrollmentRepository, currentUserService, examSchemaService);
    }

    @Bean
    SubmitExamUsecase submitExamUsecase(
            ExamRepository examRepository,
            ExamQuestionRepository examQuestionRepository,
            ExamSubmissionRepository examSubmissionRepository,
            ExamResultRepository examResultRepository,
            ClassEnrollmentRepository classEnrollmentRepository,
            CurrentUserService currentUserService,
            ExamSchemaService examSchemaService) {
        return new SubmitExamUsecase(
                examRepository, examQuestionRepository, examSubmissionRepository,
                examResultRepository, classEnrollmentRepository, currentUserService, examSchemaService);
    }

    @Bean
    GetExamsByClassUsecase getExamsByClassUsecase(
            ExamRepository examRepository,
            ClassRepository classRepository,
            CurrentUserService currentUserService) {
        return new GetExamsByClassUsecase(examRepository, classRepository, currentUserService);
    }

    @Bean
    CreateSchemaTemplateUsecase createSchemaTemplateUsecase(
            SchemaTemplateRepository schemaTemplateRepository,
            CurrentUserService currentUserService) {
        return new CreateSchemaTemplateUsecase(schemaTemplateRepository, currentUserService);
    }

    @Bean
    GetSchemaTemplatesUsecase getSchemaTemplatesUsecase(
            SchemaTemplateRepository schemaTemplateRepository) {
        return new GetSchemaTemplatesUsecase(schemaTemplateRepository);
    }
}
