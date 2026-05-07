package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.FeedbackRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.request.GetFeedbacksRequest;
import graduation_project_be.application.usecases.response.GetFeedbacksResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.domain.models.Feedback;
import graduation_project_be.domain.models.PaginatedResult;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GetFeedbacksUsecase {

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    public PaginationResponse<GetFeedbacksResponse> execute(GetFeedbacksRequest request) {
        int page = request.page() < 1 ? 0 : request.page() - 1;
        PaginatedResult<Feedback> result = feedbackRepository.findAll(page, request.size());

        List<Long> studentIds = result.getData().stream()
                .map(Feedback::getStudentId)
                .distinct()
                .toList();

        Map<Long, User> usersById = userRepository.findAllById(studentIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<GetFeedbacksResponse> responses = result.getData().stream()
                .map(f -> GetFeedbacksResponse.fromModel(f, usersById.get(f.getStudentId())))
                .toList();

        return PaginationResponse.valueOf(
                responses,
                PaginationResponse.PaginationMeta.valueOf(page, request.size(), result.getPagination().getTotal())
        );
    }
}
