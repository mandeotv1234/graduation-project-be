package graduation_project_be.adapter.web.api.dtos.response;

import graduation_project_be.application.usecases.response.GlobalSearchResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

@Data
@Builder
public class GlobalSearchResponseDto {
    private List<ClassSearchDto> classes;
    private List<ExamSearchDto> exams;
    private List<StudentSearchDto> students;
    private List<SpecSearchDto> specifications;

    @Data
    @Builder
    public static class ClassSearchDto {
        private Long id;
        private String name;
        private String teacherName;
    }

    @Data
    @Builder
    public static class ExamSearchDto {
        private Long id;
        private String title;
        private Long classId;
    }

    @Data
    @Builder
    public static class StudentSearchDto {
        private Long id;
        private String fullName;
        private String studentCode;
    }

    @Data
    @Builder
    public static class SpecSearchDto {
        private Long id;
        private String name;
    }

    public static GlobalSearchResponseDto fromResponse(GlobalSearchResponse response) {
        return GlobalSearchResponseDto.builder()
                .classes(response.getClasses().stream()
                        .map(c -> ClassSearchDto.builder()
                                .id(c.getId())
                                .name(c.getName())
                                .teacherName(c.getTeacherName())
                                .build())
                        .collect(Collectors.toList()))
                .exams(response.getExams().stream()
                        .map(e -> ExamSearchDto.builder()
                                .id(e.getId())
                                .title(e.getTitle())
                                .classId(e.getClassId())
                                .build())
                        .collect(Collectors.toList()))
                .students(response.getStudents().stream()
                        .map(s -> StudentSearchDto.builder()
                                .id(s.getId())
                                .fullName(s.getFullName())
                                .studentCode(s.getStudentCode())
                                .build())
                        .collect(Collectors.toList()))
                .specifications(response.getSpecifications().stream()
                        .map(s -> SpecSearchDto.builder()
                                .id(s.getId())
                                .name(s.getName())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
