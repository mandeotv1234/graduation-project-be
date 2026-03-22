package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.TestCaseRepository;
import graduation_project_be.domain.models.TestCase;
import graduation_project_be.infrastructure.persistence.entities.TestCaseEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.TestCaseJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class TestCaseRepositoryImpl implements TestCaseRepository {
    private final TestCaseJpaRepository jpaRepository;

    @Override
    public List<TestCase> findByQuestionId(Long questionId) {
        return jpaRepository.findByQuestionIdOrderByOrderIndexAsc(questionId).stream()
                .map(TestCaseEntity::toModel)
                .toList();
    }

    @Override
    public TestCase save(TestCase testCase) {
        TestCaseEntity entity = TestCaseEntity.fromModel(testCase);
        return jpaRepository.save(entity).toModel();
    }
}
