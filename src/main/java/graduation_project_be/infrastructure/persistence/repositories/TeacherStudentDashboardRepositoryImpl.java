package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.TeacherStudentDashboardRepository;
import graduation_project_be.application.usecases.response.GetStudentDashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TeacherStudentDashboardRepositoryImpl implements TeacherStudentDashboardRepository {

    private final JdbcTemplate jdbcTemplate;

    public TeacherStudentDashboardRepositoryImpl(javax.sql.DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public GetStudentDashboardResponse getDashboard(Long teacherId, Long studentId) {
        // 1. Get Student Info
        String studentQuery = """
            SELECT id, full_name, email, email as student_code
            FROM users
            WHERE id = ?
            """;
            
        GetStudentDashboardResponse.StudentOverviewDto student = jdbcTemplate.queryForObject(studentQuery,
            (rs, rowNum) -> GetStudentDashboardResponse.StudentOverviewDto.builder()
                .id(rs.getLong("id"))
                .fullName(rs.getString("full_name"))
                .studentCode(rs.getString("student_code"))
                .email(rs.getString("email"))
                .build(),
            studentId
        );

        // 2. Get Class Performance
        String classQuery = """
            SELECT 
                c.id as class_id,
                c.class_code,
                c.semester,
                COUNT(DISTINCT e.id) as total_exams,
                COUNT(DISTINCT es.exam_id) as submitted_exams,
                AVG(es.score_earned) as average_score
            FROM classes c
            JOIN teacher_classes tc ON tc.class_id = c.id
            JOIN class_enrollments ce ON ce.class_id = c.id
            LEFT JOIN exams e ON e.class_id = c.id
            LEFT JOIN exam_submissions es ON es.exam_id = e.id AND es.student_id = ce.student_id
            WHERE tc.teacher_id = ? 
              AND ce.student_id = ?
              AND c.deleted_at IS NULL
            GROUP BY c.id, c.class_code, c.semester
            """;
            
        List<GetStudentDashboardResponse.ClassPerformanceDto> classes = jdbcTemplate.query(classQuery,
            (rs, rowNum) -> {
                Float avgScore = rs.getObject("average_score") != null ? rs.getFloat("average_score") : null;
                return GetStudentDashboardResponse.ClassPerformanceDto.builder()
                    .classId(rs.getLong("class_id"))
                    .classCode(rs.getString("class_code"))
                    .term(rs.getString("semester"))
                    .totalExams(rs.getInt("total_exams"))
                    .submittedExams(rs.getInt("submitted_exams"))
                    .averageScore(avgScore != null ? Math.round(avgScore * 100.0f) / 100.0f : null)
                    .build();
            },
            teacherId, studentId
        );

        // 3. Calculate Overall GPA
        float sumGpa = 0;
        int countGpa = 0;
        for (GetStudentDashboardResponse.ClassPerformanceDto cls : classes) {
            if (cls.getAverageScore() != null) {
                sumGpa += cls.getAverageScore();
                countGpa++;
            }
        }
        Float overallGpa = countGpa > 0 ? (float) (Math.round((sumGpa / countGpa) * 100.0) / 100.0) : null;
        if (student != null) {
            student.setOverallGpa(overallGpa);
        }

        return GetStudentDashboardResponse.builder()
            .student(student)
            .classes(classes)
            .build();
    }
}
