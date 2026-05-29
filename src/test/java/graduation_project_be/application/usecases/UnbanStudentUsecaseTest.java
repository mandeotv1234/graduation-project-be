package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.services.CurrentUserService;
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
class UnbanStudentUsecaseTest {

    @Mock
    private ClassRepository classRepository;

    @Mock
    private ClassStudentBanRepository classStudentBanRepository;

    @Mock
    private CurrentUserService currentUserService;

    private UnbanStudentUsecase unbanStudentUsecase;

    @BeforeEach
    void setUp() {
        unbanStudentUsecase = new UnbanStudentUsecase(
                classRepository,
                classStudentBanRepository,
                currentUserService
        );
    }

    @Test
    void execute_should_unban_student_when_active_ban_exists() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;

        ClassStudentBan activeBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason("Previous violation")
                .bannedBy(currentUserId)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        ClassStudentBan unbannedBan = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(studentId)
                .reason("Previous violation")
                .bannedBy(currentUserId)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(false)
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(activeBan));
        when(classStudentBanRepository.save(any(ClassStudentBan.class))).thenReturn(unbannedBan);

        // Act
        unbanStudentUsecase.execute(classId, studentId);

        // Assert
        ArgumentCaptor<ClassStudentBan> captor = ArgumentCaptor.forClass(ClassStudentBan.class);
        verify(classStudentBanRepository).save(captor.capture());

        ClassStudentBan actualBan = captor.getValue();
        assertThat(actualBan.getId()).isEqualTo(1L);
        assertThat(actualBan.isActive()).isFalse();
        assertThat(actualBan.getClassId()).isEqualTo(classId);
        assertThat(actualBan.getStudentId()).isEqualTo(studentId);
    }

    @Test
    void execute_should_throw_UnauthorizedException_when_teacher_access_denied() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> unbanStudentUsecase.execute(classId, studentId))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User does not have access to this class");

        verify(classStudentBanRepository, never()).save(any());
    }

    @Test
    void execute_should_throw_ResourceNotFoundException_when_no_active_ban_exists() {
        // Arrange
        Long classId = 1L;
        Long studentId = 2L;
        Long currentUserId = 3L;

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> unbanStudentUsecase.execute(classId, studentId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(classStudentBanRepository, never()).save(any());
    }
}
