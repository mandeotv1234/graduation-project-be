package graduation_project_be.infrastructure.persistence.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "schema_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaTemplateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "ddl_script", nullable = false, columnDefinition = "TEXT")
    private String ddlScript;

    @Column(name = "default_data_script", columnDefinition = "TEXT")
    private String defaultDataScript;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
