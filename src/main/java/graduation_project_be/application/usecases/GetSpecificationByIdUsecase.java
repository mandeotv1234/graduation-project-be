package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.domain.models.ExamSpecification;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetSpecificationByIdUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;

    public ExamSpecificationResponse execute(Long specificationId) {
        ExamSpecification specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamSpecification", "id", specificationId));

        return ExamSpecificationResponse.fromModel(specification);
    }
}
