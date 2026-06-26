package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class RubricRefinementAiSupport {

    private RubricRefinementAiSupport() {
    }

    static String buildPrompt(
            String correctQuery,
            String questionContent,
            double totalPoints,
            String questionType,
            String priorQuestionContext,
            String schemaContext,
            String currentRubricJson,
            String teacherInstruction,
            String targetMode,
            String targetTestCaseId) {
        String normalizedQuestionType = normalizeQuestionType(questionType);
        return """
                Bạn là trợ lý thiết kế rubric/testcase SQL cho giáo viên.

                Nhiệm vụ: chỉnh rubric hiện tại theo yêu cầu bằng tiếng Việt của giáo viên, nhưng phải giữ đầy đủ ngữ cảnh và toàn bộ cấu trúc rubric.

                QUY TẮC BẮT BUỘC:
                - Trả về JSON duy nhất, không markdown, không giải thích ngoài JSON.
                - JSON trả về phải có đúng shape:
                  {
                    "rubric": { ...full GradingRubric object... },
                    "changeSummary": ["..."],
                    "warnings": ["..."]
                  }
                - "rubric" phải là rubric đầy đủ sau khi chỉnh, không phải patch/diff.
                - Giữ nguyên các field không liên quan đến yêu cầu: question_category, grading_payload, grading_settings, whitebox, rule IDs, column configs, expected data, scripts, metadata.
                - Tổng điểm phải vẫn là %.4f và không tự ý thay đổi phân bổ điểm nếu giáo viên không yêu cầu.
                - Nếu targetMode=EDIT_TEST_CASE và targetTestCaseId có giá trị, ưu tiên sửa đúng test case đó; không xóa test case khác.
                - Nếu targetMode=ADD_TEST_CASE, thêm test case/kỳ vọng mới với ID duy nhất, không ghi đè ID cũ.
                - Nếu targetMode=IMPROVE_COVERAGE, có thể thêm hoặc chỉnh ít test case cần thiết để bao phủ lỗ hổng giáo viên nêu.
                - Nếu targetMode=REBALANCE_POINTS, chỉ chỉnh điểm/mức áp dụng, giữ logic test case ổn định.
                - Với SELECT_QUERY/FUNCTION/STORED_PROCEDURE/TRIGGER, testcase thường nằm trong grading_payload.test_cases.
                - Với INSERT_DATA, kỳ vọng testcase thường nằm trong grading_payload.tables[].expected_data và columns_config.
                - Với CREATE_TABLE, kỳ vọng thường nằm trong grading_payload.expected_tables hoặc cấu trúc bảng/cột/ràng buộc tương đương.
                - Dùng schema/context câu trước để tránh tạo dữ liệu hoặc object SQL không tồn tại.

                THÔNG TIN CÂU HỎI:
                questionType=%s
                totalPoints=%.4f
                targetMode=%s
                targetTestCaseId=%s

                NỘI DUNG CÂU HỎI:
                %s

                ĐÁP ÁN MẪU / SQL THAM CHIẾU:
                %s

                SCHEMA CONTEXT:
                %s

                CONTEXT CÁC CÂU TRƯỚC:
                %s

                RUBRIC HIỆN TẠI:
                %s

                YÊU CẦU GIÁO VIÊN:
                %s
                """.formatted(
                totalPoints,
                normalizedQuestionType,
                totalPoints,
                safe(targetMode),
                safe(targetTestCaseId),
                safe(questionContent),
                safe(correctQuery),
                truncate(safe(schemaContext), 12000),
                truncate(safe(priorQuestionContext), 12000),
                truncate(safe(currentRubricJson), 28000),
                safe(teacherInstruction));
    }

    static String normalizeProviderResponse(
            String providerJson,
            String questionType,
            double totalPoints,
            ObjectMapper objectMapper) throws Exception {
        JsonNode root = objectMapper.readTree(providerJson);
        JsonNode rubric = root.has("rubric") ? root.get("rubric") : root;
        ObjectNode normalizedRubric = coerceFullRubric(rubric, questionType, totalPoints, objectMapper);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("rubric", normalizedRubric);

        ArrayNode summary = readTextArray(root, objectMapper, "changeSummary", "change_summary");
        if (summary.isEmpty()) {
            summary.add("AI đã cập nhật rubric theo yêu cầu của giáo viên.");
        }
        response.set("changeSummary", summary);
        response.set("warnings", readTextArray(root, objectMapper, "warnings"));

        return objectMapper.writeValueAsString(response);
    }

    private static ObjectNode coerceFullRubric(
            JsonNode rubric,
            String questionType,
            double totalPoints,
            ObjectMapper objectMapper) {
        if (rubric == null || !rubric.isObject()) {
            throw new IllegalArgumentException("Refined rubric must be a JSON object");
        }

        ObjectNode rubricObject = ((ObjectNode) rubric).deepCopy();
        if (!rubricObject.has("question_category")) {
            rubricObject.put("question_category", normalizeQuestionType(questionType));
        }
        if (!rubricObject.has("total_points")) {
            rubricObject.put("total_points", totalPoints);
        }
        if (!rubricObject.has("grading_payload")) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("question_category", normalizeQuestionType(questionType));
            wrapper.put("total_points", totalPoints);
            wrapper.set("grading_payload", rubricObject);
            return wrapper;
        }

        return rubricObject;
    }

    private static ArrayNode readTextArray(JsonNode root, ObjectMapper objectMapper, String... fieldNames) {
        ArrayNode result = objectMapper.createArrayNode();
        if (root == null || !root.isObject()) {
            return result;
        }

        for (String fieldName : fieldNames) {
            JsonNode values = root.get(fieldName);
            if (values == null) {
                continue;
            }
            if (values.isArray()) {
                values.forEach(value -> {
                    String text = value == null ? "" : value.asText("");
                    if (!text.isBlank()) {
                        result.add(text);
                    }
                });
            } else {
                String text = values.asText("");
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        }

        return result;
    }

    private static String normalizeQuestionType(String questionType) {
        return questionType == null || questionType.isBlank()
                ? "CREATE_TABLE"
                : questionType.trim().toUpperCase();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(trống)" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...[truncated]...";
    }
}
