package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BannedFromExamException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.DeviceConflictNotificationService;
import graduation_project_be.application.port.services.DeviceConflictStore;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.request.StartExamSessionRequest;
import graduation_project_be.domain.models.ClassStudentBan;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartExamSessionUsecaseTest {

    @Mock
    private ExamRepository examRepository;

    @Mock
    private ClassEnrollmentRepository classEnrollmentRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private ClassStudentBanRepository classStudentBanRepository;

    // Mock other dependencies required by the usecase
    @Mock
    private ExamSessionService examSessionService;

    @Mock
    private ExamResultRepository examResultRepository;

    @Mock
    private ExamSpecificationRepository examSpecificationRepository;

    @Mock
    private ExamSchemaService examSchemaService;

    @Mock
    private DeviceConflictStore deviceConflictStore;

    @Mock
    private DeviceConflictNotificationService deviceConflictNotificationService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private ExamDraftRepository examDraftRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private StartExamSessionUsecase startExamSessionUsecase;

    @BeforeEach
    void setUp() {
        startExamSessionUsecase = new StartExamSessionUsecase(
                examRepository,
                classEnrollmentRepository,
                currentUserService,
                examSessionService,
                examResultRepository,
                examSpecificationRepository,
                examSchemaService,
                deviceConflictStore,
                deviceConflictNotificationService,
                userRepository,
                classRepository,
                examDraftRepository,
                messagingTemplate,
                classStudentBanRepository
        );
    }

    @Test
    void execute_should_throw_BannedFromExamException_when_student_has_active_ban() {
        // Arrange
        Long studentId = 1L;
        Long classId = 10L;
        Long examId = 100L;
        String banReason = "Cheating violation";

        StartExamSessionRequest request = new StartExamSessionRequest(examId, "192.168.1.1", "Mozilla/5.0");

        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .isPublished(true)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(2))
                .maxAttempts(5)
                .build();

        ClassStudentBan activeBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason(banReason)
                .bannedBy(5L)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(activeBan));

        // Act & Assert
        assertThatThrownBy(() -> startExamSessionUsecase.execute(request))
                .isInstanceOf(BannedFromExamException.class)
                .hasMessage("Bạn đã bị cấm thi trong lớp này: " + banReason);
    }

    @Test
    void execute_should_throw_BannedFromExamException_with_default_message_when_no_reason() {
        // Arrange
        Long studentId = 1L;
        Long classId = 10L;
        Long examId = 100L;

        StartExamSessionRequest request = new StartExamSessionRequest(examId, "192.168.1.1", "Mozilla/5.0");

        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .isPublished(true)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(2))
                .build();

        ClassStudentBan activeBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason(null) // No reason provided
                .bannedBy(5L)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(activeBan));

        // Act & Assert
        assertThatThrownBy(() -> startExamSessionUsecase.execute(request))
                .isInstanceOf(BannedFromExamException.class)
                .hasMessage("Bạn đã bị cấm thi trong lớp này: Không có lý do");
    }

    @Test
    void execute_should_not_throw_BannedFromExamException_when_no_active_ban_exists() {
        // Arrange
        Long studentId = 1L;
        Long classId = 10L;
        Long examId = 100L;

        StartExamSessionRequest request = new StartExamSessionRequest(examId, "192.168.1.1", "Mozilla/5.0");

        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .isPublished(true)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(2))
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.empty()); // No active ban

        // Act
        // The usecase should not throw BannedFromExamException and proceed further
        // Note: other validations (time window, etc.) may fail, but not the ban check
        // This test only verifies the ban check doesn't throw the exception
        try {
            // Note: This will likely fail on other validations (device conflict check, etc.),
            // but we're specifically testing that the ban check passes without throwing BannedFromExamException
            startExamSessionUsecase.execute(request);
        } catch (BannedFromExamException ex) {
            throw new AssertionError("BannedFromExamException should not be thrown when no active ban exists", ex);
        } catch (Exception ex) {
            // Other exceptions are expected (e.g., NullPointerException from mocked dependencies)
            // We're only verifying that BannedFromExamException is NOT thrown
        }

        // Positively assert the ban lookup actually ran (guards against a future
        // refactor reordering checks so the ban verification is skipped entirely).
        verify(classStudentBanRepository).findActiveByClassIdAndStudentId(classId, studentId);
    }

    @Test
    void execute_should_reach_ban_check_after_enrollment_validation_passes() {
        // Arrange
        Long studentId = 1L;
        Long classId = 10L;
        Long examId = 100L;

        StartExamSessionRequest request = new StartExamSessionRequest(examId, "192.168.1.1", "Mozilla/5.0");

        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .isPublished(true)
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(2))
                .build();

        ClassStudentBan activeBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason("Exam violation")
                .bannedBy(5L)
                .bannedAt(LocalDateTime.now())
                .active(true)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(activeBan));

        // Act & Assert
        // Verify that ban check is performed after enrollment check passes
        assertThatThrownBy(() -> startExamSessionUsecase.execute(request))
                .isInstanceOf(BannedFromExamException.class);
    }

    @Test
    void execute_should_not_reload_schema_when_attempt_schema_was_prepared() {
        Long studentId = 1L;
        Long classId = 10L;
        Long examId = 100L;

        StartExamSessionRequest request = new StartExamSessionRequest(examId, "192.168.1.1", "Mozilla/5.0");
        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .specificationId(50L)
                .isPublished(true)
                .startTime(LocalDateTime.now().minusMinutes(10))
                .endTime(LocalDateTime.now().plusHours(2))
                .durationMinutes(60)
                .maxAttempts(3)
                .settings(ExamSettings.builder()
                        .isLoadDdl(true)
                        .seedDatasetId(7L)
                        .build())
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.empty());
        when(examSessionService.getExamStartTime(examId, studentId)).thenReturn(Optional.empty());
        when(examSessionService.tryStartSession(examId, studentId, "192.168.1.1", "Mozilla/5.0"))
                .thenReturn(true);
        when(examResultRepository.countByExamIdAndStudentId(examId, studentId)).thenReturn(0L);
        when(examSchemaService.extractMetadata("exam_100_student_1_att_1"))
                .thenReturn(List.of(TableMetadata.builder().tableName("Students").build()));

        var response = startExamSessionUsecase.execute(request);

        assertThat(response.sessionStarted()).isTrue();
        verify(examSchemaService, never()).resetSchema(anyString(), anyBoolean());
        verify(examSchemaService, never()).loadTemplateIntoSchema(
                anyString(), nullable(String.class), nullable(String.class));
        verify(examSessionService).saveExamStartTime(examId, studentId, response.examStartedAt());
    }
}
