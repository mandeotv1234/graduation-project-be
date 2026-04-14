package graduation_project_be.application.usecases;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DeleteSpecificationUsecase {

    private final ExamSpecificationRepository examSpecificationRepository;
    private final ExamRepository examRepository;

    @Transactional
    public void execute(Long id) {
        if (!examSpecificationRepository.existsById(id)) {
            throw new ResourceNotFoundException("Specification", "id", id);
        }

        if (examRepository.existsBySpecificationId(id)) {
            throw new BadRequestException(
                    "Không thể xóa đặc tả vì vẫn còn bài thi đang sử dụng đặc tả này");
        }

        examSpecificationRepository.deleteById(id);
    }
}
