package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.TeacherStudentDashboardRepository;
import graduation_project_be.application.usecases.response.GetStudentDashboardResponse;
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
        // 1. Get Student Info within the teacher's active classes
        String studentQuery = """
            SELECT DISTINCT u.id, u.full_name, u.email, u.email as student_code
            FROM users u
            JOIN class_enrollments ce ON ce.student_id = u.id
            JOIN classes c ON c.id = ce.class_id
            JOIN teacher_classes tc ON tc.class_id = c.id
            WHERE u.id = ?
              AND tc.teacher_id = ?
              AND c.deleted_at IS NULL
            """;

        List<GetStudentDashboardResponse.StudentOverviewDto> students = jdbcTemplate.query(studentQuery,
            (rs, rowNum) -> GetStudentDashboardResponse.StudentOverviewDto.builder()
                .id(rs.getLong("id"))
                .fullName(rs.getString("full_name"))
                .studentCode(rs.getString("student_code"))
                .email(rs.getString("email"))
                .build(),
            studentId, teacherId
        );

        if (students.isEmpty()) {
            throw new UnauthorizedException("Student is not enrolled in any active class managed by this teacher");
        }

        GetStudentDashboardResponse.StudentOverviewDto student = students.get(0);

        // 2. Get Class Performance
        String classQuery = """
            WITH accessible_classes AS (
                SELECT c.id, c.class_code, c.semester
                FROM classes c
                JOIN teacher_classes tc ON tc.class_id = c.id
                JOIN class_enrollments ce ON ce.class_id = c.id
                WHERE tc.teacher_id = ?
                  AND ce.student_id = ?
                  AND c.deleted_at IS NULL
            ),
            class_exams AS (
                SELECT
                    ac.id as class_id,
                    e.id as exam_id,
                    COALESCE(NULLIF(e.settings::jsonb ->> 'grading_method', ''), 'highest_score') as grading_method
                FROM accessible_classes ac
                JOIN exams e ON e.class_id = ac.id
            ),
            all_results AS (
                SELECT
                    ce.class_id,
                    er.exam_id,
                    er.attempt_number,
                    er.submitted_at,
                    er.status,
                    er.total_score,
                    er.max_score
                FROM class_exams ce
                JOIN exam_results er ON er.exam_id = ce.exam_id
                WHERE er.student_id = ?
            ),
            scored_results AS (
                SELECT *
                FROM all_results
                WHERE total_score IS NOT NULL
                  AND max_score IS NOT NULL
                  AND max_score > 0
                  AND (status IS NULL OR status NOT IN ('PENDING', 'GRADING', 'SYSTEM_ERROR'))
            ),
            latest_results AS (
                SELECT DISTINCT ON (exam_id)
                    exam_id,
                    total_score,
                    max_score,
                    status
                FROM all_results
                ORDER BY exam_id, submitted_at DESC NULLS LAST, attempt_number DESC
            ),
            highest_results AS (
                SELECT DISTINCT ON (exam_id)
                    exam_id,
                    total_score
                FROM scored_results
                ORDER BY exam_id, (total_score / max_score) DESC, attempt_number DESC
            ),
            average_results AS (
                SELECT exam_id, AVG(total_score) as total_score
                FROM scored_results
                GROUP BY exam_id
            ),
            exam_scores AS (
                SELECT
                    ce.class_id,
                    ce.exam_id,
                    CASE
                        WHEN LOWER(ce.grading_method) = 'latest_score'
                             AND lr.total_score IS NOT NULL
                             AND lr.max_score IS NOT NULL
                             AND lr.max_score > 0
                             AND (lr.status IS NULL OR lr.status NOT IN ('PENDING', 'GRADING', 'SYSTEM_ERROR'))
                            THEN lr.total_score
                        WHEN LOWER(ce.grading_method) = 'average_score'
                            THEN ar.total_score
                        ELSE hr.total_score
                    END as final_score
                FROM class_exams ce
                LEFT JOIN latest_results lr ON lr.exam_id = ce.exam_id
                LEFT JOIN highest_results hr ON hr.exam_id = ce.exam_id
                LEFT JOIN average_results ar ON ar.exam_id = ce.exam_id
            ),
            submitted_exams AS (
                SELECT DISTINCT class_id, exam_id
                FROM all_results
            )
            SELECT 
                ac.id as class_id,
                ac.class_code,
                ac.semester,
                COUNT(DISTINCT ce.exam_id) as total_exams,
                COUNT(DISTINCT se.exam_id) as submitted_exams,
                AVG(es.final_score) as average_score
            FROM accessible_classes ac
            LEFT JOIN class_exams ce ON ce.class_id = ac.id
            LEFT JOIN submitted_exams se ON se.class_id = ac.id AND se.exam_id = ce.exam_id
            LEFT JOIN exam_scores es ON es.class_id = ac.id AND es.exam_id = ce.exam_id
            GROUP BY ac.id, ac.class_code, ac.semester
            ORDER BY ac.class_code
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
            teacherId, studentId, studentId
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
        student.setOverallGpa(overallGpa);

        return GetStudentDashboardResponse.builder()
            .student(student)
            .classes(classes)
            .build();
    }
}
