package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.ResponseDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sql-metadata")
public class SqlMetadataController {

    private final JdbcTemplate examJdbcTemplate;

    public SqlMetadataController(@Qualifier("examJdbcTemplate") JdbcTemplate examJdbcTemplate) {
        this.examJdbcTemplate = examJdbcTemplate;
    }

    @GetMapping("/mssql-types")
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> getMsSqlTypes() {
        List<String> types = examJdbcTemplate.queryForList("""
                SELECT UPPER(name)
                FROM sys.types
                WHERE is_user_defined = 0
                  AND name NOT IN ('sysname', 'hierarchyid', 'geometry', 'geography')
                ORDER BY name
                """, String.class);

        return ResponseEntity.ok(ResponseDto.of(types, "OK", "Danh sách kiểu dữ liệu MSSQL"));
    }
}
