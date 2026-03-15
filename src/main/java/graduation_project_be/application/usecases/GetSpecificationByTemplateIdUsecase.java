package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.ExamSpecification;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetSpecificationByTemplateIdUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;

    public ExamSpecificationResponse execute(Long templateId) {
        ExamSpecification specification = examSpecificationRepository.findByTemplateId(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "templateId", templateId));

        return ExamSpecificationResponse.fromModel(specification);
    }
}
