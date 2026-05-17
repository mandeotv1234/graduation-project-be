package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.*;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.GeminiService;
import graduation_project_be.application.port.services.PdfRenderService;
import graduation_project_be.application.usecases.helpers.ExamPdfModelBuilder;
import graduation_project_be.application.usecases.request.ExportExamPdfRequest;
import graduation_project_be.application.usecases.response.ExportExamPdfResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.infrastructure.utils.SlugUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Export an exam to a PDF byte array.
 *
 * <p>Preconditions (in order):
 * <ol>
 *   <li>Teacher is a class teacher of the exam's class</li>
 *   <li>Exam has a specificationId and the spec has ≥1 entity → else 400 EXAM_NO_SPECIFICATION</li>
 *   <li>Exam has ≥1 question → else 400 EXAM_NO_QUESTIONS</li>
 * </ol>
 *
 * <p>Entities with blank descriptions are lazily generated via Gemini in parallel
 * (8s per-call timeout, 20s total), then persisted before rendering.
 */
@Slf4j
@RequiredArgsConstructor
public class ExportExamPdfUsecase {

    private final ExamRepository examRepository;
    private final ClassRepository classRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final SpecEntityRepository specEntityRepository;
    private final CurrentUserService currentUserService;
    private final GeminiService geminiService;
    private final PdfRenderService pdfRenderService;
    private final ExecutorService geminiExecutor;

    public ExportExamPdfResponse execute(ExportExamPdfRequest request) {
        Long userId = currentUserService.getCurrentUserId();
        Long examId = request.examId();

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        // Auth: must be class teacher
        boolean isTeacher = classRepository.existsTeacherAccess(exam.getClassId(), userId);
        if (!isTeacher) {
            throw new UnauthorizedException("You are not a teacher of this exam's class");
        }

        // Precondition 1: spec must exist and have entities
        if (exam.getSpecificationId() == null) {
            throw new BadRequestException("EXAM_NO_SPECIFICATION");
        }
        ExamSpecification spec = examSpecificationRepository.findById(exam.getSpecificationId())
                .orElseThrow(() -> new BadRequestException("EXAM_NO_SPECIFICATION"));
        if (spec.getEntities() == null || spec.getEntities().isEmpty()) {
            throw new BadRequestException("EXAM_NO_SPECIFICATION");
        }

        // Precondition 2: must have questions
        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);
        if (questions.isEmpty()) {
            throw new BadRequestException("EXAM_NO_QUESTIONS");
        }

        // Lazy-gen descriptions for entities that are blank
        lazyGenDescriptions(spec.getEntities());

        // Find active dataset
        SpecDataset activeDataset = spec.getDatasets() == null ? null : spec.getDatasets().stream()
                .filter(SpecDataset::isActive)
                .min(java.util.Comparator.comparingInt(SpecDataset::getOrderIndex))
                .orElse(null);

        // Get classCode for filename
        graduation_project_be.domain.models.Class clazz = classRepository.findById(exam.getClassId());
        String classCode = clazz != null ? clazz.getClassCode() : "CLASS";

        // Build model and render
        String regulations = request.regulationsOverride();
        Map<String, Object> model = ExamPdfModelBuilder.build(
                exam, classCode, spec, questions, regulations, activeDataset);

        byte[] pdfBytes = pdfRenderService.render("exam-paper", model);

        String fileName = "De-" + classCode + "-" + examId + "-"
                + SlugUtil.slugify(exam.getTitle() != null ? exam.getTitle() : "exam") + ".pdf";

        return new ExportExamPdfResponse(pdfBytes, fileName);
    }

    /**
     * For each entity with a blank description, fire a Gemini call on the shared executor
     * with an 8-second per-call timeout. Total wait is capped at 20 seconds.
     * Failures are swallowed — PDF renders without description for that entity.
     */
    private void lazyGenDescriptions(List<SpecEntity> entities) {
        List<SpecEntity> needsGen = entities.stream()
                .filter(e -> e.getDescription() == null || e.getDescription().isBlank())
                .collect(Collectors.toList());

        if (needsGen.isEmpty()) return;

        List<CompletableFuture<Void>> tasks = needsGen.stream()
                .map(entity -> CompletableFuture
                        .supplyAsync(() -> geminiService.generateEntityDescription(
                                entity.getEntityName(),
                                entity.getDisplayName(),
                                entity.getAttributes(),
                                null), geminiExecutor)
                        .orTimeout(8, TimeUnit.SECONDS)
                        .thenAccept(desc -> {
                            if (desc != null && !desc.isBlank()) {
                                specEntityRepository.updateDescription(entity.getId(), desc);
                                entity.setDescription(desc);
                            }
                        })
                        .exceptionally(ex -> {
                            log.warn("Lazy-gen description failed for entity '{}': {}",
                                    entity.getEntityName(), ex.getMessage());
                            return null;
                        }))
                .toList();

        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .orTimeout(20, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Lazy description generation did not complete within 20s: {}", e.getMessage());
        }
    }
}
