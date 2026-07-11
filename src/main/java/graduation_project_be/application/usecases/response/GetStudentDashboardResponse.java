package graduation_project_be.application.usecases.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetStudentDashboardResponse {
    private StudentOverviewDto student;
    private List<ClassPerformanceDto> classes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentOverviewDto {
        private Long id;
        private String fullName;
        private String studentCode;
        private String email;
        private Float overallGpa;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassPerformanceDto {
        private Long classId;
        private String classCode;
        private String term;
        private Integer totalExams;
        private Integer submittedExams;
        private Float averageScore;
    }
}
