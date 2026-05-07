package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.GetFeedbacksResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.application.usecases.GetFeedbacksUsecase;
import graduation_project_be.application.usecases.request.GetFeedbacksRequest;
import graduation_project_be.application.usecases.response.GetFeedbacksResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/feedbacks")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final GetFeedbacksUsecase getFeedbacksUsecase;

    @GetMapping
    public ResponseEntity<PaginationResponseDto<GetFeedbacksResponseDto>> getFeedbacks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        PaginationResponse<GetFeedbacksResponse> response = getFeedbacksUsecase.execute(
                new GetFeedbacksRequest(page, size));

        return ResponseEntity.ok(
                PaginationResponseDto.fromResponse(response, GetFeedbacksResponseDto::fromResponse, "200", "OK"));
    }
}
