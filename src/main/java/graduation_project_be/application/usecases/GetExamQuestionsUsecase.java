package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.usecases.response.ExamQuestionResponse;
import graduation_project_be.domain.models.ExamQuestion;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetExamQuestionsUsecase {

    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;

    public List<ExamQuestionResponse> execute(Long examId) {
        examRepository.findById(examId)
                .orElseThrow(() -> new IllegalArgumentException("Exam not found"));

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);
        return questions.stream()
                .map(ExamQuestionResponse::fromModel)
                .toList();
    }
}
