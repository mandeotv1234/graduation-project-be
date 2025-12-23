package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetClassesResponse;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GetClassesResponseDto {
    private Long id;
    private String classCode;
    private Long teacherId;
    private String semester;
    private LocalDateTime createdAt;

    public static GetClassesResponseDto fromResponse(GetClassesResponse response) {
        GetClassesResponseDto dto = new GetClassesResponseDto();
        dto.setId(response.getId());
        dto.setClassCode(response.getClassCode());
        dto.setTeacherId(response.getTeacherId());
        dto.setSemester(response.getSemester());
        dto.setCreatedAt(response.getCreatedAt());
        return dto;
    }
}
