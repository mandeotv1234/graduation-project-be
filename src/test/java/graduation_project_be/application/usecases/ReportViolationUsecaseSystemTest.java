package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ViolationNotificationService;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportViolationUsecaseSystemTest {

    private static final Long EXAM_ID = 1L;
    private static final Long STUDENT_ID = 2L;
    private static final Long CLASS_ID = 10L;
    private static final String TAMPERED = "INTEGRITY_TAMPERED";

    @Mock private ExamViolationRepository examViolationRepository;
    @Mock private ExamResultRepository examResultRepository;
    @Mock private ExamRepository examRepository;
    @Mock private ClassEnrollmentRepository classEnrollmentRepository;
    @Mock private ClassRepository classRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private ViolationNotificationService violationNotificationService;
    @Mock private SubmitExamUsecase submitExamUsecase;
    @Mock private ExamDraftRepository examDraftRepository;
    @Mock private UserRepository userRepository;
    @Captor private ArgumentCaptor<ExamViolation> violationCaptor;

    private ReportViolationUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new ReportViolationUsecase(examViolationRepository, examResultRepository, examRepository,
                classEnrollmentRepository, classRepository, currentUserService, violationNotificationService,
                submitExamUsecase, examDraftRepository, userRepository);
    }

    private Exam exam(boolean autoSubmit, Integer maxViolations) {
        return Exam.builder()
                .id(EXAM_ID)
                .classId(CLASS_ID)
                .settings(ExamSettings.builder()
                        .autoSubmitOnViolation(autoSubmit)
                        .maxViolations(maxViolations)
                        .build())
                .build();
    }

    private void commonStubs(Exam exam) {
        when(examRepository.findByIdAndIsPublished(EXAM_ID, true)).thenReturn(Optional.of(exam));
        when(classEnrollmentRepository.existsByClassIdAndStudentId(CLASS_ID, STUDENT_ID)).thenReturn(true);
        User student = mock(User.class);
        when(student.getFullName()).thenReturn("Test Student");
        when(userRepository.findById(STUDENT_ID)).thenReturn(Optional.of(student));
        when(examResultRepository.countByExamIdAndStudentId(EXAM_ID, STUDENT_ID)).thenReturn(0L);
        when(examViolationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(classRepository.findTeachersByClassId(CLASS_ID)).thenReturn(List.of());
    }

    @Test
    void executeAsSystem_savesIntegrityTampered_withoutAutoSubmit() {
        commonStubs(exam(false, null));
        when(examViolationRepository.countByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, STUDENT_ID, 1))
                .thenReturn(0L);

        usecase.executeAsSystem(EXAM_ID, STUDENT_ID, TAMPERED, "no heartbeat");

        verify(examViolationRepository).save(violationCaptor.capture());
        assertThat(violationCaptor.getValue().getViolationType()).isEqualTo(TAMPERED);
        assertThat(violationCaptor.getValue().getStudentId()).isEqualTo(STUDENT_ID);
        verify(violationNotificationService).notifyTeacher(eq(EXAM_ID), any(), eq(STUDENT_ID),
                eq("Test Student"), eq(TAMPERED), any(), anyInt(), eq(1L), eq(false));
        verify(submitExamUsecase, never()).executeAsSystem(any(), any(), eq(true));
    }

    @Test
    void executeAsSystem_firesAutoSubmit_whenMaxViolationsReached() {
        commonStubs(exam(true, 1));
        when(examViolationRepository.countByExamIdAndStudentIdAndAttemptNumber(EXAM_ID, STUDENT_ID, 1))
                .thenReturn(0L);
        when(examDraftRepository.findByExamIdAndStudentId(EXAM_ID, STUDENT_ID)).thenReturn(Optional.empty());

        usecase.executeAsSystem(EXAM_ID, STUDENT_ID, TAMPERED, "no heartbeat");

        verify(submitExamUsecase).executeAsSystem(any(), eq(STUDENT_ID), eq(true));
    }
}
