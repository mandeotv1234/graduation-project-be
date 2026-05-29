package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.BannedStudentResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.domain.models.ClassStudentBan;
import graduation_project_be.domain.models.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetClassBansUsecaseTest {

    @Mock
    private ClassRepository classRepository;

    @Mock
    private ClassStudentBanRepository classStudentBanRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserService currentUserService;

    private GetClassBansUsecase getClassBansUsecase;

    @BeforeEach
    void setUp() {
        getClassBansUsecase = new GetClassBansUsecase(
                classRepository,
                classStudentBanRepository,
                userRepository,
                currentUserService
        );
    }

    @Test
    void execute_should_return_paginated_banned_students_with_resolved_names() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 1;
        int size = 10;

        ClassStudentBan ban1 = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(2L)
                .reason("Cheating")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        ClassStudentBan ban2 = ClassStudentBan.builder()
                .id(2L)
                .classId(classId)
                .studentId(3L)
                .reason("Disruptive behavior")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(2))
                .active(true)
                .build();

        User student1 = User.builder()
                .id(2L)
                .email("student1@example.com")
                .fullName("John Doe")
                .build();

        User student2 = User.builder()
                .id(3L)
                .email("student2@example.com")
                .fullName("Jane Smith")
                .build();

        User teacher = User.builder()
                .id(10L)
                .email("teacher@example.com")
                .fullName("Teacher Name")
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassId(classId))
                .thenReturn(List.of(ban1, ban2));
        when(userRepository.findById(2L)).thenReturn(Optional.of(student1));
        when(userRepository.findById(3L)).thenReturn(Optional.of(student2));
        when(userRepository.findById(10L)).thenReturn(Optional.of(teacher));

        // Act
        PaginationResponse<BannedStudentResponse> result = getClassBansUsecase.execute(classId, page, size);

        // Assert
        assertThat(result.data()).hasSize(2);
        assertThat(result.pagination().getTotal()).isEqualTo(2);
        assertThat(result.pagination().getPage()).isEqualTo(0);
        assertThat(result.pagination().getSize()).isEqualTo(10);
        assertThat(result.pagination().getTotalPages()).isEqualTo(1);

        BannedStudentResponse response1 = result.data().get(0);
        assertThat(response1.studentId()).isEqualTo(2L);
        assertThat(response1.email()).isEqualTo("student1@example.com");
        assertThat(response1.fullName()).isEqualTo("John Doe");
        assertThat(response1.bannedByName()).isEqualTo("Teacher Name");
        assertThat(response1.reason()).isEqualTo("Cheating");

        BannedStudentResponse response2 = result.data().get(1);
        assertThat(response2.studentId()).isEqualTo(3L);
        assertThat(response2.email()).isEqualTo("student2@example.com");
        assertThat(response2.fullName()).isEqualTo("Jane Smith");
        assertThat(response2.reason()).isEqualTo("Disruptive behavior");
    }

    @Test
    void execute_should_return_empty_list_when_no_bans_exist() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 1;
        int size = 10;

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassId(classId)).thenReturn(List.of());

        // Act
        PaginationResponse<BannedStudentResponse> result = getClassBansUsecase.execute(classId, page, size);

        // Assert
        assertThat(result.data()).isEmpty();
        assertThat(result.pagination().getTotal()).isEqualTo(0);
        assertThat(result.pagination().getTotalPages()).isEqualTo(0);
    }

    @Test
    void execute_should_handle_missing_student_user_gracefully() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 1;
        int size = 10;

        ClassStudentBan ban = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(2L)
                .reason("Cheating")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        User teacher = User.builder()
                .id(10L)
                .email("teacher@example.com")
                .fullName("Teacher Name")
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassId(classId)).thenReturn(List.of(ban));
        when(userRepository.findById(2L)).thenReturn(Optional.empty()); // Student not found
        when(userRepository.findById(10L)).thenReturn(Optional.of(teacher));

        // Act
        PaginationResponse<BannedStudentResponse> result = getClassBansUsecase.execute(classId, page, size);

        // Assert
        assertThat(result.data()).hasSize(1);
        BannedStudentResponse response = result.data().get(0);
        assertThat(response.email()).isEmpty();
        assertThat(response.fullName()).isEmpty();
        assertThat(response.bannedByName()).isEqualTo("Teacher Name");
    }

    @Test
    void execute_should_handle_missing_banned_by_user_gracefully() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 1;
        int size = 10;

        ClassStudentBan ban = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(2L)
                .reason("Cheating")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        User student = User.builder()
                .id(2L)
                .email("student1@example.com")
                .fullName("John Doe")
                .build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassId(classId)).thenReturn(List.of(ban));
        when(userRepository.findById(2L)).thenReturn(Optional.of(student));
        when(userRepository.findById(10L)).thenReturn(Optional.empty()); // Banned-by user not found

        // Act
        PaginationResponse<BannedStudentResponse> result = getClassBansUsecase.execute(classId, page, size);

        // Assert
        assertThat(result.data()).hasSize(1);
        BannedStudentResponse response = result.data().get(0);
        assertThat(response.bannedByName()).isEmpty();
    }

    @Test
    void execute_should_handle_pagination_correctly_page_2() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 2;
        int size = 2;

        ClassStudentBan ban1 = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(2L)
                .reason("Cheating")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(3))
                .active(true)
                .build();

        ClassStudentBan ban2 = ClassStudentBan.builder()
                .id(2L)
                .classId(classId)
                .studentId(3L)
                .reason("Disruptive behavior")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(2))
                .active(true)
                .build();

        ClassStudentBan ban3 = ClassStudentBan.builder()
                .id(3L)
                .classId(classId)
                .studentId(4L)
                .reason("Late submission")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now().minusDays(1))
                .active(true)
                .build();

        User student1 = User.builder().id(2L).email("s1@example.com").fullName("Student 1").build();
        User student2 = User.builder().id(3L).email("s2@example.com").fullName("Student 2").build();
        User student3 = User.builder().id(4L).email("s3@example.com").fullName("Student 3").build();
        User teacher = User.builder().id(10L).email("teacher@example.com").fullName("Teacher").build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassId(classId))
                .thenReturn(List.of(ban1, ban2, ban3));
        // Only student 4 (ban3) will be shown on page 2, so only mock that
        when(userRepository.findById(4L)).thenReturn(Optional.of(student3));
        when(userRepository.findById(10L)).thenReturn(Optional.of(teacher));

        // Act
        PaginationResponse<BannedStudentResponse> result = getClassBansUsecase.execute(classId, page, size);

        // Assert
        assertThat(result.data()).hasSize(1); // Only 1 item on page 2 (3rd item)
        assertThat(result.pagination().getPage()).isEqualTo(1); // 0-based page
        assertThat(result.pagination().getSize()).isEqualTo(2);
        assertThat(result.pagination().getTotal()).isEqualTo(3);
        assertThat(result.pagination().getTotalPages()).isEqualTo(2);
        assertThat(result.data().get(0).studentId()).isEqualTo(4L);
    }

    @Test
    void execute_should_handle_invalid_page_number_as_first_page() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 0; // Invalid: should be treated as page 1
        int size = 10;

        ClassStudentBan ban = ClassStudentBan.builder()
                .id(1L)
                .classId(classId)
                .studentId(2L)
                .reason("Cheating")
                .bannedBy(10L)
                .bannedAt(LocalDateTime.now())
                .active(true)
                .build();

        User student = User.builder().id(2L).email("s1@example.com").fullName("Student 1").build();
        User teacher = User.builder().id(10L).email("teacher@example.com").fullName("Teacher").build();

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(true);
        when(classStudentBanRepository.findActiveByClassId(classId)).thenReturn(List.of(ban));
        when(userRepository.findById(2L)).thenReturn(Optional.of(student));
        when(userRepository.findById(10L)).thenReturn(Optional.of(teacher));

        // Act
        PaginationResponse<BannedStudentResponse> result = getClassBansUsecase.execute(classId, page, size);

        // Assert
        assertThat(result.data()).hasSize(1);
        assertThat(result.pagination().getPage()).isEqualTo(0); // 0-based, treated as first page
    }

    @Test
    void execute_should_throw_UnauthorizedException_when_teacher_access_denied() {
        // Arrange
        Long classId = 1L;
        Long currentUserId = 10L;
        int page = 1;
        int size = 10;

        when(currentUserService.getCurrentUserId()).thenReturn(currentUserId);
        when(classRepository.existsTeacherAccess(classId, currentUserId)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> getClassBansUsecase.execute(classId, page, size))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("User does not have access to this class");
    }
}
