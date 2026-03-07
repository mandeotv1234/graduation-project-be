package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.request.CreateSchemaTemplateRequestDto;
import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import graduation_project_be.adapter.web.api.dtos.response.SchemaTemplateResponseDto;
import graduation_project_be.application.usecases.CreateSchemaTemplateUsecase;
import graduation_project_be.application.usecases.GetSchemaTemplatesUsecase;
import graduation_project_be.application.usecases.response.SchemaTemplateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/schema-templates")
@RequiredArgsConstructor
public class SchemaTemplateController {

    private final CreateSchemaTemplateUsecase createSchemaTemplateUsecase;
    private final GetSchemaTemplatesUsecase getSchemaTemplatesUsecase;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> createSchemaTemplate(
            @RequestBody @Valid CreateSchemaTemplateRequestDto requestDto) {
        SchemaTemplateResponse response = createSchemaTemplateUsecase.execute(requestDto.toRequest());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(
                        SchemaTemplateResponseDto.fromResponse(response),
                        "CREATED",
                        "Schema template created successfully"));
    }

    @GetMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getSchemaTemplates() {
        List<SchemaTemplateResponse> responses = getSchemaTemplatesUsecase.execute();
        List<SchemaTemplateResponseDto> dtos = responses.stream()
                .map(SchemaTemplateResponseDto::fromResponse)
                .toList();
        return ResponseEntity.ok(
                ResponseDto.of(dtos, "OK", "Schema templates retrieved successfully"));
    }
}
