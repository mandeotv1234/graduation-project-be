package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.GlobalSearchRepository;
import graduation_project_be.application.usecases.response.GlobalSearchResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.util.List;

@Repository
public class GlobalSearchRepositoryImpl implements GlobalSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public GlobalSearchRepositoryImpl(DataSource dataSource) {
        // dataSource will resolve to the @Primary (PostgreSQL) DataSource
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public GlobalSearchResponse searchAll(String keyword, Long teacherId) {
        String likeKeyword = "%" + keyword + "%";

        // 1. Search Classes
        String classQuery = """
            SELECT c.id, c.class_code as name, u.full_name as teacher_name
            FROM classes c
            JOIN teacher_classes tc ON tc.class_id = c.id
            JOIN users u ON u.id = c.creator_id
            WHERE tc.teacher_id = ?
              AND c.deleted_at IS NULL
              AND (c.class_code ILIKE ? OR ? ILIKE '%' || c.class_code || '%')
            LIMIT 5
            """;
            
        List<GlobalSearchResponse.ClassSearchDto> classes = jdbcTemplate.query(classQuery,
            (rs, rowNum) -> GlobalSearchResponse.ClassSearchDto.builder()
                    .id(rs.getLong("id"))
                    .name(rs.getString("name"))
                    .teacherName(rs.getString("teacher_name"))
                    .build(),
            teacherId, likeKeyword, keyword);

        // 2. Search Exams
        String examQuery = """
            SELECT e.id, e.title, e.class_id
            FROM exams e
            JOIN classes c ON c.id = e.class_id
            JOIN teacher_classes tc ON tc.class_id = e.class_id
            WHERE tc.teacher_id = ?
              AND c.deleted_at IS NULL
              AND (e.title ILIKE ? OR ? ILIKE '%' || e.title || '%')
            LIMIT 5
            """;
            
        List<GlobalSearchResponse.ExamSearchDto> exams = jdbcTemplate.query(examQuery,
            (rs, rowNum) -> GlobalSearchResponse.ExamSearchDto.builder()
                    .id(rs.getLong("id"))
                    .title(rs.getString("title"))
                    .classId(rs.getLong("class_id"))
                    .build(),
            teacherId, likeKeyword, keyword);

        // 3. Search Students (in teacher's classes)
        String studentQuery = """
            SELECT DISTINCT u.id, u.full_name, u.email as student_code
            FROM users u
            JOIN class_enrollments ce ON ce.student_id = u.id
            JOIN classes c ON c.id = ce.class_id
            JOIN teacher_classes tc ON tc.class_id = ce.class_id
            WHERE tc.teacher_id = ?
              AND c.deleted_at IS NULL
              AND (u.full_name ILIKE ? OR ? ILIKE '%' || u.full_name || '%' 
                   OR u.email ILIKE ? OR ? ILIKE '%' || u.email || '%')
            LIMIT 5
            """;
            
        List<GlobalSearchResponse.StudentSearchDto> students = jdbcTemplate.query(studentQuery,
            (rs, rowNum) -> GlobalSearchResponse.StudentSearchDto.builder()
                    .id(rs.getLong("id"))
                    .fullName(rs.getString("full_name"))
                    .studentCode(rs.getString("student_code"))
                    .build(),
            teacherId, likeKeyword, keyword, likeKeyword, keyword);

        // 4. Search Specifications (created by this teacher)
        String specQuery = """
            SELECT s.id, s.name
            FROM specifications s
            WHERE s.created_by = ?
              AND (s.name ILIKE ? OR ? ILIKE '%' || s.name || '%')
            LIMIT 5
            """;
            
        List<GlobalSearchResponse.SpecSearchDto> specs = jdbcTemplate.query(specQuery,
            (rs, rowNum) -> GlobalSearchResponse.SpecSearchDto.builder()
                    .id(rs.getLong("id"))
                    .name(rs.getString("name"))
                    .build(),
            teacherId, likeKeyword, keyword);

        return GlobalSearchResponse.builder()
                .classes(classes)
                .exams(exams)
                .students(students)
                .specifications(specs)
                .build();
    }
}
