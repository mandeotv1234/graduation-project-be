package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.SubmitFeedbackRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.SubmitFeedbackResponseDto;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.SubmitFeedbackUsecase;
import graduation_project_be.application.usecases.response.SubmitFeedbackResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/feedbacks")
@RequiredArgsConstructor
@Validated
public class FeedbackController {

    private final SubmitFeedbackUsecase submitFeedbackUsecase;
    private final CurrentUserService currentUserService;

    @PostMapping
    @PreAuthorize("hasAnyRole('STUDENT', 'TEACHER')")
    public ResponseEntity<ResponseDto> submitFeedback(
            @RequestBody @Valid SubmitFeedbackRequestDto requestDto) {
        
        Long currentUserId = currentUserService.getCurrentUserId();
        SubmitFeedbackResponse response = submitFeedbackUsecase.execute(requestDto.toRequest(currentUserId));
        
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(
                        SubmitFeedbackResponseDto.fromResponse(response),
                        "CREATED",
                        "Feedback submitted successfully"));
    }
}
