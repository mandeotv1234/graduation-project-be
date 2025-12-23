package graduation_project_be.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassEnrollmentId implements Serializable {
    private Long classId;
    private Long studentId;
}
