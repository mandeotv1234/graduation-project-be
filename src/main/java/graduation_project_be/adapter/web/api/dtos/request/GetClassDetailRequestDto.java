package graduation_project_be.adapter.web.api.dtos.request;

import graduation_project_be.application.usecases.request.GetClassDetailRequest;
import lombok.Builder;

@Builder
public record GetClassDetailRequestDto(
        Long classId) {
    public GetClassDetailRequest toRequest() {
        return GetClassDetailRequest.builder()
                .classId(classId)
                .build();
    }
}