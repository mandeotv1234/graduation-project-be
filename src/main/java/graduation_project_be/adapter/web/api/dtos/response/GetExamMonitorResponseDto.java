package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GetExamMonitorResponse;

import java.time.LocalDateTime;
import java.util.List;

public record GetExamMonitorResponseDto(
        Long examId,
        String examTitle,
        Long classId,
        String classCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Boolean isPublished,
        int totalStudents,
        int totalViolators,
        List<GetExamMonitorStudentResponseDto> students) {

    public static GetExamMonitorResponseDto fromResponse(GetExamMonitorResponse response) {
        List<GetExamMonitorStudentResponseDto> studentDtos = response.students().stream()
                .map(GetExamMonitorStudentResponseDto::fromResponse)
                .toList();

        return new GetExamMonitorResponseDto(
                response.examId(),
                response.examTitle(),
                response.classId(),
                response.classCode(),
                response.startTime(),
                response.endTime(),
                response.isPublished(),
                response.totalStudents(),
                response.totalViolators(),
                studentDtos);
    }
}
