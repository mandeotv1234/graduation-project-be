package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.GlobalSearchResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.application.usecases.GlobalSearchUsecase;
import graduation_project_be.application.usecases.request.GlobalSearchRequest;
import graduation_project_be.application.usecases.response.GlobalSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final GlobalSearchUsecase globalSearchUsecase;

    @GetMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> globalSearch(@RequestParam("q") String keyword) {
        GlobalSearchRequest request = GlobalSearchRequest.builder()
                .keyword(keyword)
                .build();
                
        GlobalSearchResponse response = globalSearchUsecase.execute(request);
        
        return ResponseEntity.ok(
                ResponseDto.of(
                        GlobalSearchResponseDto.fromResponse(response),
                        "OK",
                        "Search completed successfully"));
    }
}
