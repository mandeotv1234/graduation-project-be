package graduation_project_be.infrastructure.persistence.entities;

import java.io.Serializable;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeacherClassId implements Serializable{
    private Long classId;
    private Long teacherId;
    
}
