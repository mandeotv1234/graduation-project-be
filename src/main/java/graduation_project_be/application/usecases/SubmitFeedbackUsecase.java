package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.FeedbackRepository;
import graduation_project_be.application.usecases.request.SubmitFeedbackRequest;
import graduation_project_be.application.usecases.response.SubmitFeedbackResponse;
import graduation_project_be.domain.models.Feedback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SubmitFeedbackUsecase {

    private final FeedbackRepository feedbackRepository;

    public SubmitFeedbackResponse execute(SubmitFeedbackRequest request) {
        Feedback feedback = Feedback.builder()
                .studentId(request.studentId())
                .examId(request.examId())
                .uiUxRating(request.uiUxRating())
                .systemReliabilityRating(request.systemReliabilityRating())
                .npsScore(request.npsScore())
                .featureRequests(request.featureRequests())
                .generalFeedback(request.generalFeedback())
                .build();

        Feedback savedFeedback = feedbackRepository.save(feedback);
        return SubmitFeedbackResponse.fromModel(savedFeedback);
    }
}
