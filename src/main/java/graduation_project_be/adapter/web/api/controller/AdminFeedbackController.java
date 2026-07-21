package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.GetFeedbacksResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.PaginationResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.GetFeedbackDetailUsecase;
import graduation_project_be.application.usecases.GetFeedbacksUsecase;
import graduation_project_be.application.usecases.request.GetFeedbacksRequest;
import graduation_project_be.application.usecases.response.GetFeedbacksResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import lombok.RequiredArgsConstructor;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/feedbacks")
@RequiredArgsConstructor
@Validated
public class AdminFeedbackController {

    private final GetFeedbacksUsecase getFeedbacksUsecase;
    private final GetFeedbackDetailUsecase getFeedbackDetailUsecase;

    @GetMapping
    public ResponseEntity<PaginationResponseDto<GetFeedbacksResponseDto>> getFeedbacks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        PaginationResponse<GetFeedbacksResponse> response = getFeedbacksUsecase.execute(
                new GetFeedbacksRequest(page, size));

        return ResponseEntity.ok(
                PaginationResponseDto.fromResponse(response, GetFeedbacksResponseDto::fromResponse, "200", "OK"));
    }

    @GetMapping("/{feedbackId}")
    public ResponseEntity<ResponseDto> getFeedbackDetail(
            @PathVariable @Positive Long feedbackId) {
        GetFeedbacksResponse response = getFeedbackDetailUsecase.execute(feedbackId);

        return ResponseEntity.ok(ResponseDto.of(
                GetFeedbacksResponseDto.fromResponse(response),
                "OK",
                "Feedback retrieved successfully"));
    }
}
