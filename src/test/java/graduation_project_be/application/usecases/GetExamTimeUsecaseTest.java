package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.shared.utils.TimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetExamTimeUsecaseTest {

    @Mock
    private ExamRepository examRepository;
    @Mock
    private ClassEnrollmentRepository classEnrollmentRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ExamSessionService examSessionService;

    private GetExamTimeUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new GetExamTimeUsecase(
                examRepository, classEnrollmentRepository, currentUserService, examSessionService);
    }

    @Test
    void execute_should_count_down_late_window_instead_of_ending_at_regular_deadline() {
        long examId = 10L;
        long studentId = 20L;
        long classId = 30L;
        LocalDateTime now = TimeUtils.now();
        LocalDateTime startedAt = now.minusMinutes(10);
        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .durationMinutes(60)
                .startTime(now.minusHours(2))
                .endTime(now.minusMinutes(1))
                .lateThreshold(5)
                .settings(ExamSettings.builder().allowOvertime(true).build())
                .isPublished(true)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(examSessionService.getExamStartTime(examId, studentId)).thenReturn(Optional.of(startedAt));

        var response = usecase.execute(examId);

        assertThat(response.status()).isEqualTo("LATE_SUBMISSION");
        assertThat(response.expired()).isFalse();
        assertThat(response.remainingSeconds()).isBetween(235L, 240L);
    }

    @Test
    void execute_should_end_after_late_submission_deadline() {
        long examId = 10L;
        long studentId = 20L;
        long classId = 30L;
        LocalDateTime now = TimeUtils.now();
        LocalDateTime startedAt = now.minusMinutes(66);
        Exam exam = Exam.builder()
                .id(examId)
                .classId(classId)
                .durationMinutes(60)
                .startTime(now.minusHours(2))
                .endTime(now.plusHours(1))
                .lateThreshold(5)
                .settings(ExamSettings.builder().allowOvertime(true).build())
                .isPublished(true)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(studentId);
        when(examRepository.findByIdAndIsPublished(examId, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(examSessionService.getExamStartTime(examId, studentId)).thenReturn(Optional.of(startedAt));

        var response = usecase.execute(examId);

        assertThat(response.status()).isEqualTo("ENDED");
        assertThat(response.expired()).isTrue();
        assertThat(response.remainingSeconds()).isZero();
    }
}
