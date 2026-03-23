package graduation_project_be.infrastructure.persistence.repositories.jpa;

import graduation_project_be.infrastructure.persistence.entities.TestCaseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestCaseJpaRepository extends JpaRepository<TestCaseEntity, Long> {
    List<TestCaseEntity> findByQuestionIdOrderByOrderIndexAsc(Long questionId);
}
