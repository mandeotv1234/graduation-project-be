package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.TeacherClassNotificationService;
import graduation_project_be.application.usecases.request.AddTeacherToClassRequest;
import graduation_project_be.domain.models.Class;
import graduation_project_be.domain.models.TeacherClass;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherClassMembershipUsecaseTest {

    @Mock
    private ClassRepository classRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private TeacherClassNotificationService notificationService;

    private Class clazz;
    private User actor;
    private User invitedTeacher;

    @BeforeEach
    void setUp() {
        clazz = Class.builder()
                .id(10L)
                .classCode("CSDL-K24")
                .semester("2026-1")
                .creatorId(1L)
                .build();
        actor = User.builder()
                .id(1L)
                .email("owner@fit.hcmus.edu.vn")
                .fullName("Owner")
                .role(Role.TEACHER)
                .isActive(true)
                .build();
        invitedTeacher = User.builder()
                .id(2L)
                .email("manh@vng.com.vn")
                .fullName("Mạnh Huỳnh")
                .role(Role.TEACHER)
                .isActive(true)
                .build();
    }

    @Test
    void addTeacherPublishesNotificationWithLoggedInTeacher() {
        when(classRepository.findById(10L)).thenReturn(clazz);
        when(currentUserService.getCurrentUser()).thenReturn(actor);
        when(classRepository.existsTeacherAccess(10L, 1L)).thenReturn(true);
        when(userRepository.findByEmail(invitedTeacher.getEmail())).thenReturn(Optional.of(invitedTeacher));
        when(classRepository.existsTeacherAccess(10L, 2L)).thenReturn(false);
        when(classRepository.saveTeacherAssociation(any(TeacherClass.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AddTeacherToClassUsecase usecase = new AddTeacherToClassUsecase(
                classRepository, userRepository, currentUserService, notificationService);

        usecase.execute(AddTeacherToClassRequest.builder()
                .classId(10L)
                .email(invitedTeacher.getEmail())
                .build());

        verify(notificationService).notifyTeacherAdded(clazz, invitedTeacher, actor);
    }

    @Test
    void addTeacherRejectsOtherVngAccounts() {
        when(classRepository.findById(10L)).thenReturn(clazz);
        when(currentUserService.getCurrentUser()).thenReturn(actor);
        when(classRepository.existsTeacherAccess(10L, 1L)).thenReturn(true);

        AddTeacherToClassUsecase usecase = new AddTeacherToClassUsecase(
                classRepository, userRepository, currentUserService, notificationService);

        assertThatThrownBy(() -> usecase.execute(AddTeacherToClassRequest.builder()
                .classId(10L)
                .email("other@vng.com.vn")
                .build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not allowed");

        verify(userRepository, never()).findByEmail("other@vng.com.vn");
    }

    @Test
    void removeTeacherPublishesNotificationWithLoggedInCreator() {
        when(classRepository.findById(10L)).thenReturn(clazz);
        when(currentUserService.getCurrentUser()).thenReturn(actor);
        when(classRepository.existsTeacherAccess(10L, 2L)).thenReturn(true);
        when(userRepository.findById(2L)).thenReturn(Optional.of(invitedTeacher));

        RemoveTeacherFromClassUsecase usecase = new RemoveTeacherFromClassUsecase(
                classRepository, userRepository, currentUserService, notificationService);

        usecase.execute(10L, 2L);

        verify(classRepository).deleteTeacherAssociation(10L, 2L);
        verify(notificationService).notifyTeacherRemoved(clazz, invitedTeacher, actor);
    }
}
