package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.repositories.ExamViolationRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.usecases.response.GetExamStatisticsResponse;
import graduation_project_be.application.usecases.response.GetExamStatisticsResponse.QuestionTypeAccuracy;
import graduation_project_be.application.usecases.response.GetExamStatisticsResponse.ScoreDistributionBucket;
import graduation_project_be.application.usecases.response.GetExamStatisticsResponse.SuspiciousStudent;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.ExamViolation;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class GetExamStatisticsUsecase {

    private final ExamRepository examRepository;
    private final ExamResultRepository examResultRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamViolationRepository examViolationRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final UserRepository userRepository;

    public GetExamStatisticsResponse execute(Long examId) {
        if (examRepository.findById(examId).isEmpty()) {
            throw new ResourceNotFoundException("Exam", "id", examId);
        }

        List<ExamResult> results = examResultRepository.findByExamId(examId);
        List<ExamSubmission> submissions = examSubmissionRepository.findByExamId(examId);
        List<ExamViolation> violations = examViolationRepository.findByExamId(examId);
        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId);

        // questionId → questionType name
        Map<Long, String> questionTypeMap = questions.stream()
                .filter(q -> q.getQuestionType() != null)
                .collect(Collectors.toMap(ExamQuestion::getId, q -> q.getQuestionType().name()));

        // ===== KPIs =====
        int totalSubmissions = results.size();

        double averageScore = 0.0;
        double maxScore = 0.0;
        double minScore = 0.0;
        double passRate = 0.0;

        List<Double> scores = results.stream()
                .filter(r -> r.getTotalScore() != null && r.getMaxScore() != null
                        && r.getMaxScore().compareTo(BigDecimal.ZERO) > 0)
                .map(r -> r.getTotalScore().divide(r.getMaxScore(), 4, RoundingMode.HALF_UP).doubleValue() * 10.0)
                .toList();

        if (!scores.isEmpty()) {
            averageScore = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            maxScore = scores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            minScore = scores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            long passCount = scores.stream().filter(s -> s >= 5.0).count();
            passRate = (double) passCount / scores.size() * 100.0;
        }

        // ===== Score Distribution =====
        int bucket0_4 = 0, bucket4_6 = 0, bucket6_8 = 0, bucket8_10 = 0;
        for (double s : scores) {
            if (s < 4.0) bucket0_4++;
            else if (s < 6.0) bucket4_6++;
            else if (s < 8.0) bucket6_8++;
            else bucket8_10++;
        }
        List<ScoreDistributionBucket> scoreDistribution = List.of(
                new ScoreDistributionBucket("0–4", bucket0_4),
                new ScoreDistributionBucket("4–6", bucket4_6),
                new ScoreDistributionBucket("6–8", bucket6_8),
                new ScoreDistributionBucket("8–10", bucket8_10)
        );

        // ===== Skill Analysis — by QuestionType =====
        Map<String, int[]> typeStats = new HashMap<>(); // int[]{totalAttempts, correctCount}
        for (ExamSubmission sub : submissions) {
            String type = questionTypeMap.get(sub.getQuestionId());
            if (type == null) continue;
            typeStats.computeIfAbsent(type, k -> new int[]{0, 0});
            typeStats.get(type)[0]++;
            if (Boolean.TRUE.equals(sub.getIsCorrect())) {
                typeStats.get(type)[1]++;
            }
        }
        List<QuestionTypeAccuracy> questionTypeAccuracy = typeStats.entrySet().stream()
                .map(e -> {
                    int total = e.getValue()[0];
                    int correct = e.getValue()[1];
                    double accuracy = total > 0 ? round2((double) correct / total * 100.0) : 0.0;
                    return new QuestionTypeAccuracy(e.getKey(), total, correct, accuracy);
                })
                .sorted(Comparator.comparing(QuestionTypeAccuracy::questionType))
                .toList();

        // ===== Behavior — Avg Completion Time =====
        // lateDurationSeconds: positive = nộp muộn, negative (or field missing) = nộp sớm
        // We use it as a proxy. A simpler metric: average |lateDurationSeconds| in minutes.
        double avgCompletionTimeMinutes = results.stream()
                .mapToDouble(r -> Math.abs(r.getLateDurationSeconds()))
                .average()
                .orElse(0.0) / 60.0;

        // ===== Suspicious Students =====
        Map<Long, Long> violationCountMap = violations.stream()
                .collect(Collectors.groupingBy(ExamViolation::getStudentId, Collectors.counting()));

        int suspiciousCount = violationCountMap.size();

        List<Long> suspiciousIds = violationCountMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(10)
                .map(Map.Entry::getKey)
                .toList();

        Map<Long, User> userMap = userRepository.findAllById(suspiciousIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<SuspiciousStudent> suspiciousStudents = suspiciousIds.stream()
                .map(sid -> {
                    User u = userMap.get(sid);
                    return new SuspiciousStudent(
                            sid,
                            u != null ? u.getFullName() : "Unknown",
                            u != null ? u.getEmail() : "Unknown",
                            violationCountMap.get(sid).intValue()
                    );
                })
                .toList();

        return new GetExamStatisticsResponse(
                totalSubmissions,
                round2(averageScore),
                round2(maxScore),
                round2(minScore),
                round2(passRate),
                suspiciousCount,
                scoreDistribution,
                questionTypeAccuracy,
                round2(avgCompletionTimeMinutes),
                suspiciousStudents
        );
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
