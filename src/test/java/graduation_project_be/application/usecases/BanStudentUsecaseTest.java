package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ConflictException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.request.BanStudentRequest;
import graduation_project_be.domain.models.ClassStudentBan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BanStudentUsecaseTest {

    @Mock
    private ClassRepository classRepository;

    @Mock
    private ClassEnrollmentRepository classEnrollmentRepository;

    @Mock
    private ClassStudentBanRepository classStudentBanRepository;

    @Mock
    private CurrentUserService currentUserService;

    private BanStudentUsecase banStudentUsecase;

    @BeforeEach
    void setUp() {
        banStudentUsecase = new BanStudentUsecase(
                classRepository,
                classEnrollmentRepository,
                classStudentBanRepository,
                currentUserService
        );
    }

    @Test
    void execute_should_ban_student_when_all_validations_pass() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;
        String reason = "Cheating detected";

        BanStudentRequest request = new BanStudentRequest(classId, studentId, reason);

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classRepository.existsTeacherAccess(classId, studentId)).thenReturn(false);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.empty());

        ClassStudentBan savedBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason(reason)
                .bannedBy(currentUserId)
                .active(true)
                .build();

        when(classStudentBanRepository.save(any(ClassStudentBan.class))).thenReturn(savedBan);

        // Act
        banStudentUsecase.execute(request);

        // Assert
        ArgumentCaptor<ClassStudentBan> captor = ArgumentCaptor.forClass(ClassStudentBan.class);
        verify(classStudentBanRepository).save(captor.capture());

        ClassStudentBan actualBan = captor.getValue();
        assertThat(actualBan.getClassId()).isEqualTo(classId);
        assertThat(actualBan.getStudentId()).isEqualTo(studentId);
        assertThat(actualBan.getReason()).isEqualTo(reason);
        assertThat(actualBan.getBannedBy()).isEqualTo(currentUserId);
        assertThat(actualBan.isActive()).isTrue();
    }

    @Test
    void execute_should_throw_UnauthorizedException_when_teacher_access_denied() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;
        String reason = "Cheating detected";

        BanStudentRequest request = new BanStudentRequest(classId, studentId, reason);

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> banStudentUsecase.execute(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User does not have access to this class");

        verify(classStudentBanRepository, never()).save(any());
    }

    @Test
    void execute_should_throw_BadRequestException_when_student_not_enrolled() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;
        String reason = "Cheating detected";

        BanStudentRequest request = new BanStudentRequest(classId, studentId, reason);

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> banStudentUsecase.execute(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Sinh viên không thuộc lớp này");

        verify(classStudentBanRepository, never()).save(any());
    }

    @Test
    void execute_should_throw_BadRequestException_when_self_ban() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 2L; // Same as studentId - self-ban attempt

        BanStudentRequest request = new BanStudentRequest(classId, studentId, "Self-ban");

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> banStudentUsecase.execute(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Không thể tự cấm bản thân");

        verify(classStudentBanRepository, never()).save(any());
    }

    @Test
    void execute_should_throw_BadRequestException_when_target_is_teacher() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;

        BanStudentRequest request = new BanStudentRequest(classId, studentId, "Cannot ban teacher");

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classRepository.existsTeacherAccess(classId, studentId)).thenReturn(true); // Target is teacher

        // Act & Assert
        assertThatThrownBy(() -> banStudentUsecase.execute(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Không thể cấm giáo viên");

        verify(classStudentBanRepository, never()).save(any());
    }

    @Test
    void execute_should_throw_ConflictException_when_active_ban_exists() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;

        BanStudentRequest request = new BanStudentRequest(classId, studentId, "Duplicate ban");

        ClassStudentBan existingBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason("Previous ban")
                .bannedBy(currentUserId)
                .active(true)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classEnrollmentRepository.existsByClassIdAndStudentId(classId, studentId)).thenReturn(true);
        when(classRepository.existsTeacherAccess(classId, studentId)).thenReturn(false);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(existingBan));

        // Act & Assert
        assertThatThrownBy(() -> banStudentUsecase.execute(request))
                .isInstanceOf(ConflictException.class);

        verify(classStudentBanRepository, never()).save(any());
    }
}
