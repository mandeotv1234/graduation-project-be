package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.services.AIService;

import java.util.ArrayList;
import java.util.List;

final class StudentFeedbackAiSupport {

    private StudentFeedbackAiSupport() {
    }

    static String buildPrompt(AIService.StudentFeedbackContext context, ObjectMapper objectMapper) throws Exception {
        String contextJson = objectMapper.writeValueAsString(context);
        return """
                Bạn là AI Agent hỗ trợ học tập SQL sau khi bài thi đã được chấm.
                Nhiệm vụ: đọc điểm, lịch sử attempts, grading trace và viết feedback bằng tiếng Việt cho sinh viên.

                Ràng buộc bắt buộc:
                - Không thay đổi điểm, không gợi ý khi sinh viên đang thi, không đưa đáp án đầy đủ để chép lại.
                - Chỉ giải thích dựa trên dữ liệu được cung cấp. Nếu thiếu bằng chứng thì nói ở mức khái quát.
                - Feedback phải cụ thể theo lỗi: JOIN, điều kiện WHERE, ORDER BY, PK/FK, kiểu dữ liệu, test case, trigger/procedure/function nếu có.
                - Giọng văn thẳng, dễ hiểu, có định hướng học tập.
                - Chỉ trả JSON hợp lệ, không markdown.
                - Nếu trace item có trường `mutation_type=<loại>`, hãy dùng loại đó để chỉ đích danh lỗi sinh viên mắc:
                  MISSING_JOIN_CONDITION=thiếu điều kiện JOIN, WRONG_JOIN_TYPE=sai loại JOIN, NULL_HANDLING=xử lý NULL sai,
                  WRONG_AGGREGATE=hàm tổng hợp sai, MISSING_GROUP_BY=thiếu GROUP BY, WRONG_HAVING_VS_WHERE=nhầm HAVING/WHERE,
                  STRING_MATCHING=điều kiện chuỗi sai, MISSING_WHERE_FILTER=thiếu điều kiện lọc.
                - Nếu trace item có `expected=` và `actual=`, đây là dữ liệu counterexample từ hệ thống chấm điểm.
                  Hãy trích dẫn cụ thể để giải thích sự khác biệt (ví dụ: "Query trả 2 dòng nhưng đáp án cần 3 dòng").
                  Đây là bằng chứng trực tiếp — ưu tiên dùng thay vì suy đoán.
                - Nếu trace message chứa [CARDINAL_MISMATCH], sinh viên trả sai số dòng — đây là lỗi nghiêm trọng (JOIN/WHERE sai).
                  Nếu chứa [FULL_MISMATCH], số dòng đúng nhưng giá trị sai — thường do sai cột SELECT hoặc tính toán sai.

                Schema JSON trả về:
                {
                  "overallFeedback": "2-4 câu nhận xét tổng quan",
                  "progressFeedback": "1-3 câu về tiến bộ qua các lần thi",
                  "strengths": ["điểm mạnh cụ thể"],
                  "weaknesses": ["điểm cần cải thiện cụ thể"],
                  "studyAdvice": ["việc nên ôn/luyện tiếp theo"],
                  "questionFeedbacks": [
                    {
                      "questionId": 1,
                      "diagnosis": "nhận xét chính của câu",
                      "mistakes": ["lỗi cụ thể"],
                      "advice": ["cách ôn/tự kiểm tra cho lỗi này"]
                    }
                  ]
                }

                Dữ liệu bài làm:
                %s
                """.formatted(contextJson);
    }

    static AIService.StudentFeedbackDraft parseDraft(String text, ObjectMapper objectMapper) throws Exception {
        if (text == null || text.isBlank()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(stripMarkdown(text));
        return new AIService.StudentFeedbackDraft(
                textOrNull(root.path("overallFeedback")),
                textOrNull(root.path("progressFeedback")),
                readStringList(root.path("strengths")),
                readStringList(root.path("weaknesses")),
                readStringList(root.path("studyAdvice")),
                readQuestionFeedbacks(root.path("questionFeedbacks"))
        );
    }

    private static List<AIService.StudentQuestionFeedbackDraft> readQuestionFeedbacks(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AIService.StudentQuestionFeedbackDraft> feedbacks = new ArrayList<>();
        for (JsonNode item : node) {
            Long questionId = item.path("questionId").canConvertToLong()
                    ? item.path("questionId").asLong()
                    : null;
            if (questionId == null) {
                continue;
            }
            feedbacks.add(new AIService.StudentQuestionFeedbackDraft(
                    questionId,
                    textOrNull(item.path("diagnosis")),
                    readStringList(item.path("mistakes")),
                    readStringList(item.path("advice"))
            ));
        }
        return feedbacks;
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static String stripMarkdown(String text) {
        return text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
    }
}
