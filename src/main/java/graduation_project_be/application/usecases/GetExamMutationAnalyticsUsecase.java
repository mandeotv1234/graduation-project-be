package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.usecases.response.GetExamMutationAnalyticsResponse;
import graduation_project_be.application.usecases.response.GetExamMutationAnalyticsResponse.GlobalInsights;
import graduation_project_be.application.usecases.response.GetExamMutationAnalyticsResponse.MutationStat;
import graduation_project_be.application.usecases.response.GetExamMutationAnalyticsResponse.QuestionMutationSummary;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.GradingTrace;
import graduation_project_be.domain.models.GradingTraceItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class GetExamMutationAnalyticsUsecase {

    private static final Map<String, String> MUTATION_LABELS = Map.of(
            "HAPPY_PATH", "Logic chính",
            "MISSING_JOIN_CONDITION", "Thiếu điều kiện JOIN",
            "WRONG_JOIN_TYPE", "Sai loại JOIN",
            "MISSING_WHERE_FILTER", "Thiếu điều kiện WHERE",
            "STRING_MATCHING", "Điều kiện chuỗi sai",
            "NULL_HANDLING", "Xử lý NULL sai",
            "WRONG_AGGREGATE", "Hàm tổng hợp sai",
            "MISSING_GROUP_BY", "Thiếu GROUP BY",
            "WRONG_HAVING_VS_WHERE", "Nhầm HAVING/WHERE"
    );

    private static final Map<String, String> STUDY_RECOMMENDATIONS = Map.of(
            "MISSING_JOIN_CONDITION", "Ôn lại cú pháp JOIN ON và composite key conditions",
            "WRONG_JOIN_TYPE", "Ôn lại sự khác biệt INNER JOIN vs LEFT/RIGHT OUTER JOIN",
            "NULL_HANDLING", "Ôn lại xử lý NULL: ISNULL(), COALESCE(), IS NULL vs = NULL",
            "WRONG_AGGREGATE", "Ôn lại các hàm tổng hợp SUM/COUNT/AVG và khi cần DISTINCT",
            "MISSING_GROUP_BY", "Ôn lại GROUP BY và điều kiện bắt buộc phải có GROUP BY",
            "WRONG_HAVING_VS_WHERE", "Ôn lại sự khác biệt WHERE (trước GROUP BY) vs HAVING (sau GROUP BY)",
            "STRING_MATCHING", "Ôn lại cú pháp LIKE và các pattern %, _",
            "MISSING_WHERE_FILTER", "Ôn lại cách viết điều kiện lọc trong mệnh đề WHERE",
            "HAPPY_PATH", "Ôn lại cú pháp cơ bản SELECT, FROM, WHERE"
    );

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ObjectMapper objectMapper;

    public GetExamMutationAnalyticsResponse execute(Long examId) {
        if (examRepository.findById(examId).isEmpty()) {
            throw new ResourceNotFoundException("Exam", "id", examId);
        }

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);
        List<ExamSubmission> submissions = examSubmissionRepository.findByExamId(examId);
        List<ExamResult> results = examResultRepository.findByExamId(examId);

        Set<Long> uniqueStudents = submissions.stream()
                .map(ExamSubmission::getStudentId)
                .collect(Collectors.toSet());
        int totalStudents = uniqueStudents.size();

        // questionId → (mutationType → accumulator)
        Map<Long, Map<String, MutationAccumulator>> accMap = new HashMap<>();
        // questionId → Set<studentId> who passed (isCorrect=true)
        Map<Long, Set<Long>> passedByQuestion = new HashMap<>();

        for (ExamSubmission sub : submissions) {
            if (Boolean.TRUE.equals(sub.getIsCorrect())) {
                passedByQuestion.computeIfAbsent(sub.getQuestionId(), k -> new HashSet<>())
                        .add(sub.getStudentId());
            }
            GradingTrace trace = parseTrace(sub.getGradingTraceJson());
            if (trace == null || trace.items() == null) continue;
            for (GradingTraceItem item : trace.items()) {
                if (!GradingTraceItem.KIND_TEST_CASE.equals(item.kind())) continue;
                if (!GradingTraceItem.STATUS_FAIL.equals(item.status())) continue;
                String mutationType = extractMutationType(item.configSummary());
                if (mutationType == null || mutationType.isBlank()) continue;
                accMap.computeIfAbsent(sub.getQuestionId(), k -> new HashMap<>())
                        .computeIfAbsent(mutationType, k -> new MutationAccumulator())
                        .add(sub.getStudentId(), item.deductedPoints());
            }
        }

        // questionId → orderIndex, title, maxPoints
        Map<Long, ExamQuestion> questionMap = questions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, q -> q));
        Map<Long, Double> avgScoreByQuestion = computeAvgScoreByQuestion(submissions, questionMap);

        List<QuestionMutationSummary> summaries = questions.stream()
                .filter(q -> q.getQuestionType() != null)
                .sorted(Comparator.comparingInt(q -> q.getOrderIndex() != null ? q.getOrderIndex() : 0))
                .map(q -> buildSummary(q, accMap.getOrDefault(q.getId(), Map.of()),
                        passedByQuestion.getOrDefault(q.getId(), Set.of()),
                        totalStudents, avgScoreByQuestion.getOrDefault(q.getId(), 0.0)))
                .toList();

        GlobalInsights insights = buildGlobalInsights(accMap, totalStudents);

        return new GetExamMutationAnalyticsResponse(examId, totalStudents, summaries, insights);
    }

    private QuestionMutationSummary buildSummary(
            ExamQuestion question,
            Map<String, MutationAccumulator> mutAcc,
            Set<Long> passedStudents,
            int totalStudents,
            double avgScore) {

        double maxScore = question.getPoints() != null ? question.getPoints().doubleValue() : 0.0;
        double passRate = totalStudents > 0 ? (double) passedStudents.size() / totalStudents : 0.0;

        List<MutationStat> breakdown = mutAcc.entrySet().stream()
                .map(e -> {
                    String type = e.getKey();
                    MutationAccumulator acc = e.getValue();
                    int failCount = acc.uniqueStudents.size();
                    double failRate = totalStudents > 0 ? (double) failCount / totalStudents : 0.0;
                    double avgDeduction = acc.totalDeduction > 0 && acc.count > 0
                            ? round2(acc.totalDeduction / acc.count) : 0.0;
                    return new MutationStat(type, MUTATION_LABELS.getOrDefault(type, type),
                            failCount, round2(failRate), avgDeduction);
                })
                .sorted(Comparator.comparingInt(MutationStat::failCount).reversed())
                .toList();

        List<String> warnings = buildWarnings(breakdown, totalStudents);

        String title = question.getContent() != null && !question.getContent().isBlank()
                ? (question.getContent().length() > 80
                        ? question.getContent().substring(0, 80) + "…"
                        : question.getContent())
                : "Câu " + (question.getOrderIndex() != null ? question.getOrderIndex() : question.getId());

        return new QuestionMutationSummary(
                question.getId(),
                question.getOrderIndex() != null ? question.getOrderIndex() : 0,
                title,
                round2(avgScore),
                maxScore,
                round2(passRate),
                breakdown,
                warnings);
    }

    private List<String> buildWarnings(List<MutationStat> breakdown, int totalStudents) {
        List<String> warnings = new ArrayList<>();
        if (totalStudents < 5) return warnings;
        for (MutationStat stat : breakdown) {
            if (stat.failRate() > 0.75) {
                warnings.add(stat.label() + ": " + Math.round(stat.failRate() * 100)
                        + "% sinh viên fail — kiểm tra lại độ khó hoặc mô tả đề");
            }
            if ("HAPPY_PATH".equals(stat.mutationType()) && stat.failRate() > 0.50) {
                warnings.add("Hơn 50% sinh viên fail test cơ bản — logic câu hỏi có thể chưa rõ");
            }
        }
        if (breakdown.stream().allMatch(s -> s.failRate() < 0.05) && !breakdown.isEmpty()) {
            warnings.add("Tất cả loại bẫy đều ít sinh viên mắc — rubric có thể chưa đủ thách thức");
        }
        return warnings;
    }

    private GlobalInsights buildGlobalInsights(Map<Long, Map<String, MutationAccumulator>> accMap, int totalStudents) {
        Map<String, Integer> globalFailCounts = new HashMap<>();
        for (Map<String, MutationAccumulator> qAcc : accMap.values()) {
            for (Map.Entry<String, MutationAccumulator> e : qAcc.entrySet()) {
                globalFailCounts.merge(e.getKey(), e.getValue().uniqueStudents.size(), Integer::sum);
            }
        }
        List<String> topTypes = globalFailCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();

        List<String> recommendations = topTypes.stream()
                .map(t -> STUDY_RECOMMENDATIONS.getOrDefault(t, "Ôn lại kỹ năng SQL liên quan đến " + t))
                .toList();

        return new GlobalInsights(topTypes, recommendations);
    }

    private Map<Long, Double> computeAvgScoreByQuestion(List<ExamSubmission> submissions,
                                                         Map<Long, ExamQuestion> questionMap) {
        Map<Long, List<Double>> scoresByQuestion = new HashMap<>();
        for (ExamSubmission sub : submissions) {
            if (sub.getScoreEarned() != null) {
                scoresByQuestion.computeIfAbsent(sub.getQuestionId(), k -> new ArrayList<>())
                        .add(sub.getScoreEarned().doubleValue());
            }
        }
        Map<Long, Double> result = new HashMap<>();
        scoresByQuestion.forEach((qId, scores) -> {
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            result.put(qId, round2(avg));
        });
        return result;
    }

    private String extractMutationType(String configSummary) {
        if (configSummary == null) return null;
        int idx = configSummary.indexOf("mutation_type=");
        if (idx < 0) return null;
        return configSummary.substring(idx + "mutation_type=".length()).trim();
    }

    private GradingTrace parseTrace(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, GradingTrace.class);
        } catch (Exception e) {
            log.debug("Cannot parse grading trace: {}", e.getMessage());
            return null;
        }
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static class MutationAccumulator {
        final Set<Long> uniqueStudents = new HashSet<>();
        double totalDeduction = 0.0;
        int count = 0;

        void add(Long studentId, BigDecimal deduction) {
            uniqueStudents.add(studentId);
            if (deduction != null) {
                totalDeduction += deduction.doubleValue();
            }
            count++;
        }
    }
}
