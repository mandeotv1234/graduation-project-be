package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.FeedbackRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.response.GetFeedbacksResponse;
import graduation_project_be.domain.models.Feedback;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetFeedbackDetailUsecaseTest {

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private UserRepository userRepository;

    private GetFeedbackDetailUsecase usecase;

    @BeforeEach
    void setUp() {
        usecase = new GetFeedbackDetailUsecase(feedbackRepository, userRepository);
    }

    @Test
    void execute_returnsFeedbackWithStudentInformation() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 18, 11, 30);
        Feedback feedback = Feedback.builder()
                .id(12L)
                .studentId(24L)
                .examId(16L)
                .uiUxRating(4)
                .systemReliabilityRating(3)
                .npsScore(7)
                .featureRequests("Show grading progress")
                .generalFeedback("The feedback is helpful")
                .createdAt(createdAt)
                .build();
        User student = User.builder()
                .id(24L)
                .fullName("Nguyen Van A")
                .email("24122003@student.hcmus.edu.vn")
                .role(Role.STUDENT)
                .build();

        when(feedbackRepository.findById(12L)).thenReturn(Optional.of(feedback));
        when(userRepository.findById(24L)).thenReturn(Optional.of(student));

        GetFeedbacksResponse response = usecase.execute(12L);

        assertThat(response.id()).isEqualTo(12L);
        assertThat(response.studentName()).isEqualTo("Nguyen Van A");
        assertThat(response.studentEmail()).isEqualTo("24122003@student.hcmus.edu.vn");
        assertThat(response.generalFeedback()).isEqualTo("The feedback is helpful");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void execute_throwsWhenFeedbackDoesNotExist() {
        when(feedbackRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> usecase.execute(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
