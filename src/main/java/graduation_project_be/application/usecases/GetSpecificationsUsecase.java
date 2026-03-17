package graduation_project_be.application.usecases;

import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.usecases.response.SpecificationResponse;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetSpecificationsUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;

    public List<SpecificationResponse> execute() {
        return examSpecificationRepository.findAll().stream()
                .map(SpecificationResponse::fromModel)
                .toList();
    }
}
