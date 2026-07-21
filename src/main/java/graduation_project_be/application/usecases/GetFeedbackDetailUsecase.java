package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.FeedbackRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.response.GetFeedbacksResponse;
import graduation_project_be.domain.models.Feedback;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetFeedbackDetailUsecase {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    public GetFeedbacksResponse execute(Long feedbackId) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", "id", feedbackId));
        User student = userRepository.findById(feedback.getStudentId()).orElse(null);

        return GetFeedbacksResponse.fromModel(feedback, student);
    }
}
