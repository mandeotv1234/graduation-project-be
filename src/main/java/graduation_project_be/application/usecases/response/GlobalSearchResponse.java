package graduation_project_be.application.usecases.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GlobalSearchResponse {
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
}
