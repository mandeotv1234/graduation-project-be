package graduation_project_be.application.usecases.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamSpecification;
import graduation_project_be.domain.models.SpecAttribute;
import graduation_project_be.domain.models.SpecDataset;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.shared.utils.HtmlSanitizer;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the Thymeleaf model map used by exam-paper.html from domain objects.
 * Extracted for testability — no Spring dependency.
 */
@Slf4j
public class ExamPdfModelBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern QUESTION_PREFIX_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:<p>\\s*)?(?:<strong>\\s*)?(?:Câu|Cau)\\s*\\d+\\s*(?:\\([^)]*\\))?\\s*:?\\s*(?:</strong>\\s*)?");

    private ExamPdfModelBuilder() {
    }

    /**
     * Build the template variable map consumed by exam-paper.html.
     *
     * @param exam              the exam to render
     * @param classCode         the class code (e.g. "CS2023")
     * @param spec              the specification with entities + attributes loaded
     * @param questions         ordered list of exam questions
     * @param regulationsText   optional regulations override (null = skip section)
     * @param activeDataset     optional active dataset (null = skip data section)
     */
    public static Map<String, Object> build(
            Exam exam,
            String classCode,
            ExamSpecification spec,
            List<ExamQuestion> questions,
            String regulationsText,
            SpecDataset activeDataset) {

        Map<String, Object> model = new LinkedHashMap<>();

        // Header: "Đề {examId} – Môn Cơ sở dữ liệu – {duration} phút"
        model.put("examTitle", buildTitle(exam));
        model.put("examId", exam.getId());
        model.put("classCode", classCode);
        model.put("durationMinutes", exam.getDurationMinutes());
        model.put("regulations", regulationsText);

        // Entities (MÔ TẢ CSDL section)
        List<Map<String, Object>> entityModels = buildEntityModels(spec);
        model.put("entities", entityModels);

        // Questions (YÊU CẦU section)
        List<Map<String, Object>> questionModels = buildQuestionModels(questions);
        model.put("questions", questionModels);

        // Dataset tables (DỮ LIỆU MẪU section)
        List<Map<String, Object>> dataTables = buildDataTables(activeDataset);
        model.put("dataTables", dataTables);

        return model;
    }

    private static String buildTitle(Exam exam) {
        return String.format("Đề %d – Môn Cơ sở dữ liệu – %d phút",
                exam.getId(),
                exam.getDurationMinutes() != null ? exam.getDurationMinutes() : 60);
    }

    private static List<Map<String, Object>> buildEntityModels(ExamSpecification spec) {
        if (spec == null || spec.getEntities() == null) {
            return List.of();
        }

        return spec.getEntities().stream()
                .sorted(Comparator.comparingInt(SpecEntity::getOrderIndex))
                .map(entity -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("entityName", entity.getEntityName());
                    m.put("displayName", entity.getDisplayName());
                    m.put("description", entity.getDescription());

                    List<Map<String, Object>> attrs = new ArrayList<>();
                    if (entity.getAttributes() != null) {
                        entity.getAttributes().stream()
                                .sorted(Comparator.comparingInt(SpecAttribute::getOrderIndex))
                                .forEach(attr -> {
                                    Map<String, Object> a = new LinkedHashMap<>();
                                    a.put("attributeName", attr.getAttributeName());
                                    a.put("dataType", attr.getDataType());
                                    a.put("description", attr.getDescription());
                                    a.put("primaryKey", attr.isPrimaryKey());
                                    a.put("nullable", attr.isNullable());
                                    attrs.add(a);
                                });
                    }
                    m.put("attributes", attrs);
                    return m;
                })
                .toList();
    }

    private static List<Map<String, Object>> buildQuestionModels(List<ExamQuestion> questions) {
        if (questions == null) {
            return List.of();
        }

        return questions.stream()
                .sorted(Comparator.comparingInt(q -> (q.getOrderIndex() != null ? q.getOrderIndex() : 0)))
                .map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("formattedPoints", formatPoints(q.getPoints()));
                    m.put("sanitizedContent", HtmlSanitizer.clean(stripQuestionPrefix(q.getContent())));
                    return m;
                })
                .toList();
    }

    /** Keep exam-style score precision: 2.5 -> "2.50", 1 -> "1.00". */
    static String formatPoints(BigDecimal points) {
        if (points == null) {
            return "0.00";
        }
        return points.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String stripQuestionPrefix(String content) {
        if (content == null) {
            return "";
        }
        return QUESTION_PREFIX_PATTERN.matcher(content).replaceFirst("");
    }

    /**
     * Parse tableData JSON: [{tableName, columns:[], rows:[[]], ...}, ...].
     * Returns empty list on null input or parse errors.
     */
    static List<Map<String, Object>> buildDataTables(SpecDataset activeDataset) {
        if (activeDataset == null || activeDataset.getTableData() == null
                || activeDataset.getTableData().isBlank()) {
            return List.of();
        }

        try {
            JsonNode arr = MAPPER.readTree(activeDataset.getTableData());
            if (!arr.isArray()) {
                return List.of();
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode tableNode : arr) {
                String tableName = tableNode.path("tableName").asText("");
                if (tableName.isBlank()) {
                    continue;
                }

                List<String> columns = new ArrayList<>();
                tableNode.path("columns").forEach(c -> columns.add(c.asText()));

                List<List<String>> rows = new ArrayList<>();
                tableNode.path("rows").forEach(rowNode -> {
                    List<String> row = new ArrayList<>();
                    rowNode.forEach(cell -> row.add(cell.isNull() ? "" : cell.asText()));
                    rows.add(row);
                });

                Map<String, Object> tableMap = new LinkedHashMap<>();
                tableMap.put("tableName", tableName);
                tableMap.put("columns", columns);
                tableMap.put("rows", rows);
                result.add(tableMap);
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse tableData JSON for dataset {}: {}", activeDataset.getId(), e.getMessage());
            return List.of();
        }
    }
}
