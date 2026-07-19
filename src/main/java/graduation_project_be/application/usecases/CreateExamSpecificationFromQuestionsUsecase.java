package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.ExamSchemaService;
import graduation_project_be.application.port.services.PdfStorageService;
import graduation_project_be.application.port.services.PdfTextExtractor;
import graduation_project_be.application.usecases.response.ExamSpecificationResponse;
import graduation_project_be.application.usecases.support.SpecificationSchemaJsonBuilder;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.domain.models.TableMetadata;
import graduation_project_be.shared.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class CreateExamSpecificationFromQuestionsUsecase {

    private final ClassRepository classRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final ExamSpecificationRepository examSpecificationRepository;
    private final CurrentUserService currentUserService;
    private final ExamSchemaService examSchemaService;
    private final PdfStorageService pdfStorageService;
    private final PdfTextExtractor pdfTextExtractor;
    private final SpecificationSchemaJsonBuilder schemaJsonBuilder;

    @Transactional
    public ExamSpecificationResponse execute(Long examId) {
        Long currentUserId = currentUserService.getCurrentUserId();
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));

        if (!classRepository.existsTeacherAccess(exam.getClassId(), currentUserId)) {
            throw new UnauthorizedException("You do not have access to this exam");
        }

        if (exam.getSpecificationId() != null) {
            ExamSpecification current = examSpecificationRepository.findById(exam.getSpecificationId())
                    .orElse(null);
            if (current != null) {
                return ExamSpecificationResponse.fromModel(current);
            }
            log.warn("Exam {} references missing specification {}. Recreating it from questions.",
                    examId, exam.getSpecificationId());
            exam.setSpecificationId(null);
        }

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId).stream()
                .sorted(Comparator.comparing(
                        ExamQuestion::getOrderIndex,
                        Comparator.nullsLast(Integer::compareTo)))
                .toList();
        String ddlScript = joinAnswers(questions, QuestionType.CREATE_TABLE);
        String dataScript = joinAnswers(questions, QuestionType.INSERT_DATA);

        if (ddlScript.isBlank()) {
            throw new BadRequestException("Không tìm thấy đáp án CREATE TABLE hợp lệ để tạo đặc tả.");
        }
        if (dataScript.isBlank()) {
            throw new BadRequestException("Không tìm thấy đáp án INSERT DATA hợp lệ để tạo bộ dữ liệu mẫu.");
        }

        List<TableMetadata> metadata = loadAndInspectSchema(examId, currentUserId, ddlScript, dataScript);
        if (metadata.isEmpty()) {
            throw new BadRequestException("Đáp án CREATE TABLE không tạo ra bảng nào để lập đặc tả.");
        }

        String pdfText = readPdfText(exam);
        String sourceName = sourceName(exam);
        JsonNode schemaJson = schemaJsonBuilder.build(metadata);
        LocalDateTime now = TimeUtils.now();
        List<SpecEntity> entities = buildEntities(metadata, pdfText, sourceName);
        SpecDataset dataset = SpecDataset.builder()
                .name("Dữ liệu mẫu từ " + sourceName)
                .dataScript(dataScript)
                .orderIndex(1)
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ExamSpecification specification = ExamSpecification.builder()
                .name(trimToLength("Đặc tả CSDL - " + exam.getTitle(), 255))
                .ddlScript(ddlScript)
                .schemaJson(schemaJson.toString())
                .schemaDiagram(schemaJson.toString())
                .description("Đặc tả được tạo từ " + sourceName
                        + " bằng đáp án CREATE TABLE và INSERT DATA của đề thi.")
                .entities(entities)
                .datasets(List.of(dataset))
                .createdBy(currentUserId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        ExamSpecification saved = examSpecificationRepository.save(specification);
        exam.setSpecificationId(saved.getId());
        examRepository.save(exam);

        log.info("Created specification {} from exam {} questions and attached it to the exam",
                saved.getId(), examId);
        return ExamSpecificationResponse.fromModel(saved);
    }

    private String joinAnswers(List<ExamQuestion> questions, QuestionType type) {
        return questions.stream()
                .filter(question -> question.getQuestionType() == type)
                .map(ExamQuestion::getCorrectQuery)
                .filter(this::isUsableSql)
                .map(String::trim)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private boolean isUsableSql(String sql) {
        return sql != null && !sql.isBlank() && !sql.stripLeading().startsWith("--");
    }

    private List<TableMetadata> loadAndInspectSchema(
            Long examId,
            Long currentUserId,
            String ddlScript,
            String dataScript) {
        String schemaName = String.format(
                "pdf_spec_%d_%d_%d",
                examId,
                currentUserId,
                System.currentTimeMillis());
        try {
            examSchemaService.loadTemplateIntoSchema(schemaName, ddlScript, dataScript);
            return examSchemaService.extractMetadata(schemaName);
        } catch (Exception e) {
            throw new BadRequestException("Không thể tạo đặc tả từ đáp án SQL: " + e.getMessage());
        } finally {
            try {
                examSchemaService.dropSchema(schemaName);
            } catch (Exception e) {
                log.warn("Could not clean temporary schema {}: {}", schemaName, e.getMessage());
            }
        }
    }

    private String readPdfText(Exam exam) {
        if (exam.getPdfFilePath() == null || exam.getPdfFilePath().isBlank()) {
            return "";
        }
        try {
            return pdfTextExtractor.extract(pdfStorageService.loadPdf(exam.getPdfFilePath()));
        } catch (Exception e) {
            log.warn("Could not read PDF metadata for exam {}: {}", exam.getId(), e.getMessage());
            return "";
        }
    }

    private List<SpecEntity> buildEntities(
            List<TableMetadata> metadata,
            String pdfText,
            String sourceName) {
        return java.util.stream.IntStream.range(0, metadata.size())
                .mapToObj(index -> {
                    TableMetadata table = metadata.get(index);
                    String displayName = extractDisplayName(pdfText, table.getTableName());
                    List<SpecAttribute> attributes = java.util.stream.IntStream
                            .range(0, table.getColumns().size())
                            .mapToObj(columnIndex -> {
                                TableMetadata.ColumnMetadata column = table.getColumns().get(columnIndex);
                                return SpecAttribute.builder()
                                        .attributeName(column.getColumnName())
                                        .dataType(rawDataType(column))
                                        .description(extractAttributeDescription(pdfText, column.getColumnName()))
                                        .isPrimaryKey(column.isPrimaryKey())
                                        .isNullable(column.isNullable())
                                        .orderIndex(columnIndex + 1)
                                        .build();
                            })
                            .toList();

                    return SpecEntity.builder()
                            .entityName(table.getTableName())
                            .displayName(displayName)
                            .description("Bảng " + displayName + " được trích xuất từ " + sourceName + ".")
                            .orderIndex(index + 1)
                            .attributes(attributes)
                            .build();
                })
                .toList();
    }

    private String extractDisplayName(String pdfText, String tableName) {
        if (pdfText == null || pdfText.isBlank()) {
            return tableName;
        }
        Pattern pattern = Pattern.compile(
                "(?im)^\\s*" + Pattern.quote(tableName) + "\\s+([^\\r\\n\\f]+)$");
        Matcher matcher = pattern.matcher(pdfText);
        if (!matcher.find()) {
            return tableName;
        }
        String value = matcher.group(1).trim();
        return value.isBlank() || value.length() > 120 ? tableName : value;
    }

    private String extractAttributeDescription(String pdfText, String attributeName) {
        if (pdfText == null || pdfText.isBlank()) {
            return attributeName;
        }
        Pattern pattern = Pattern.compile(
                "(?im)^\\s*" + Pattern.quote(attributeName)
                        + "\\s*$\\R(?:\\s*\\R)*\\s*([^\\r\\n\\f]+)");
        Matcher matcher = pattern.matcher(pdfText);
        if (!matcher.find()) {
            return attributeName;
        }

        String value = matcher.group(1).trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (value.isBlank()
                || value.length() > 180
                || normalized.equals(attributeName.toLowerCase(Locale.ROOT))
                || normalized.equals("thuộc tính")
                || normalized.equals("mô tả")) {
            return attributeName;
        }
        return value;
    }

    private String rawDataType(TableMetadata.ColumnMetadata column) {
        return column.getRawDataType() != null ? column.getRawDataType() : column.getDataType();
    }

    private String sourceName(Exam exam) {
        String fileName = exam.getOriginalPdfFileName();
        if (fileName != null && !fileName.isBlank()) {
            return "file PDF " + fileName.trim();
        }
        return "file PDF của đề " + exam.getTitle();
    }

    private String trimToLength(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
