package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.ClassEnrollment;
import java.util.List;

public interface ClassEnrollmentRepository {
    List<ClassEnrollment> saveAll(List<ClassEnrollment> enrollments);
}
