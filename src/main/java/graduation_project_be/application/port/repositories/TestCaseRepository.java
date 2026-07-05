package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.TestCase;
import java.util.List;

public interface TestCaseRepository {
    List<TestCase> findByQuestionId(Long questionId);
    TestCase save(TestCase testCase);
    void deleteByQuestionId(Long questionId);
}
