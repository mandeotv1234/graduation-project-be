package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ExamResultFeedbackRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.services.AIService;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.GetStudentFeedbackResponse;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResultFeedback;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.GradingTrace;
import graduation_project_be.domain.models.GradingTraceItem;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.shared.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class GetStudentFeedbackUsecase {

    private static final int MAX_TRACE_MESSAGES_FOR_AI = 8;
    private static final int MAX_EVIDENCE_ITEMS = 5;

    private final ExamResultRepository examResultRepository;
    private final ExamResultFeedbackRepository examResultFeedbackRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamRepository examRepository;
    private final CurrentUserService currentUserService;
    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public GetStudentFeedbackResponse execute(Long resultId) {
        Long studentId = currentUserService.getCurrentUserId();

        ExamResult result = examResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("ExamResult", "id", resultId));

        if (!Objects.equals(result.getStudentId(), studentId)) {
            throw new UnauthorizedException("Bạn không có quyền xem feedback của kết quả này.");
        }

        if (result.getStatus() != GradingStatus.COMPLETED) {
            throw new BadRequestException("Feedback chỉ khả dụng sau khi bài thi đã chấm xong.");
        }

        Exam exam = examRepository.findById(result.getExamId())
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", result.getExamId()));

        if (exam.getSettings() == null || !Boolean.TRUE.equals(exam.getSettings().getAllowReview())) {
            throw new UnauthorizedException("Giáo viên không cho phép xem lại feedback bài làm này.");
        }

        GetStudentFeedbackResponse cachedFeedback = loadStoredFeedback(result);
        if (cachedFeedback != null) {
            return cachedFeedback;
        }

        List<ExamResult> attempts = examResultRepository
                .findByStudentIdAndExamIdIn(studentId, List.of(exam.getId()))
                .stream()
                .filter(item -> Objects.equals(item.getExamId(), exam.getId()))
                .filter(item -> item.getStatus() == GradingStatus.COMPLETED)
                .sorted(Comparator
                        .comparingInt(ExamResult::getAttemptNumber)
                        .thenComparing(ExamResult::getSubmittedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<QuestionContext> questionContexts = loadQuestionContexts(result);
        GetStudentFeedbackResponse fallback = buildFallbackResponse(exam, result, attempts, questionContexts);

        AIService.StudentFeedbackDraft aiDraft = generateAiDraft(fallback, questionContexts);
        if (aiDraft == null) {
            return fallback;
        }

        GetStudentFeedbackResponse feedback = mergeAiDraft(fallback, aiDraft);
        saveFeedbackSnapshot(result, feedback);
        return feedback;
    }

    private GetStudentFeedbackResponse loadStoredFeedback(ExamResult result) {
        return examResultFeedbackRepository.findByExamResultId(result.getId())
                .filter(snapshot -> !isFeedbackStale(result, snapshot))
                .map(ExamResultFeedback::getFeedbackJson)
                .map(this::parseStoredFeedback)
                .orElse(null);
    }

    private boolean isFeedbackStale(ExamResult result, ExamResultFeedback snapshot) {
        return result.getLastGradedAt() != null
                && snapshot.getGeneratedAt() != null
                && snapshot.getGeneratedAt().isBefore(result.getLastGradedAt());
    }

    private GetStudentFeedbackResponse parseStoredFeedback(String feedbackJson) {
        if (feedbackJson == null || feedbackJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(feedbackJson, GetStudentFeedbackResponse.class);
        } catch (Exception e) {
            log.warn("Cannot parse stored student feedback snapshot: {}", e.getMessage());
            return null;
        }
    }

    private void saveFeedbackSnapshot(ExamResult result, GetStudentFeedbackResponse feedback) {
        try {
            ExamResultFeedback existing = examResultFeedbackRepository.findByExamResultId(result.getId())
                    .orElse(null);
            var now = TimeUtils.now();
            examResultFeedbackRepository.save(ExamResultFeedback.builder()
                    .id(existing != null ? existing.getId() : null)
                    .examResultId(result.getId())
                    .examId(result.getExamId())
                    .studentId(result.getStudentId())
                    .attemptNumber(result.getAttemptNumber())
                    .generatedByAi(feedback.generatedByAi())
                    .generatedAt(feedback.generatedAt() != null ? feedback.generatedAt() : now)
                    .feedbackJson(objectMapper.writeValueAsString(feedback))
                    .createdAt(existing != null ? existing.getCreatedAt() : now)
                    .updatedAt(now)
                    .build());
        } catch (Exception e) {
            log.warn("Cannot save student feedback snapshot for result {}: {}", result.getId(), e.getMessage());
        }
    }

    private List<QuestionContext> loadQuestionContexts(ExamResult result) {
        List<ExamQuestion> questions = examQuestionRepository.findByExamId(result.getExamId());
        List<ExamSubmission> submissions = examSubmissionRepository.findByExamIdAndStudentIdAndAttemptNumber(
                result.getExamId(),
                result.getStudentId(),
                result.getAttemptNumber());

        Map<Long, ExamSubmission> submissionByQuestionId = submissions.stream()
                .collect(Collectors.toMap(ExamSubmission::getQuestionId, submission -> submission));

        List<QuestionContext> contexts = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            ExamQuestion question = questions.get(i);
            ExamSubmission submission = submissionByQuestionId.get(question.getId());
            contexts.add(new QuestionContext(
                    question,
                    submission,
                    parseTrace(submission != null ? submission.getGradingTraceJson() : null),
                    i + 1));
        }
        return contexts;
    }

    private GetStudentFeedbackResponse buildFallbackResponse(
            Exam exam,
            ExamResult result,
            List<ExamResult> attempts,
            List<QuestionContext> questionContexts) {
        GetStudentFeedbackResponse.ProgressSummary progress = buildProgressSummary(result, attempts);
        List<GetStudentFeedbackResponse.QuestionFeedback> questionFeedbacks = questionContexts.stream()
                .map(this::buildQuestionFeedback)
                .toList();

        List<String> strengths = buildStrengths(result, progress, questionFeedbacks);
        List<String> weaknesses = buildWeaknesses(questionFeedbacks);

        return new GetStudentFeedbackResponse(
                result.getId(),
                exam.getId(),
                exam.getTitle(),
                result.getAttemptNumber(),
                valueOrZero(result.getTotalScore()),
                valueOrZero(result.getMaxScore()),
                result.getSubmittedAt(),
                false,
                TimeUtils.now(),
                progress,
                buildOverallFeedback(result),
                buildProgressFeedback(progress),
                strengths,
                weaknesses,
                buildStudyAdvice(questionFeedbacks, progress),
                questionFeedbacks);
    }

    private GetStudentFeedbackResponse.ProgressSummary buildProgressSummary(
            ExamResult current,
            List<ExamResult> attempts) {
        List<ExamResult> effectiveAttempts = attempts.isEmpty() ? List.of(current) : attempts;
        ExamResult first = effectiveAttempts.get(0);
        ExamResult best = effectiveAttempts.stream()
                .max(Comparator.comparingDouble(this::scorePercent))
                .orElse(current);

        BigDecimal sum = effectiveAttempts.stream()
                .map(ExamResult::getTotalScore)
                .map(this::valueOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(effectiveAttempts.size()), 2, RoundingMode.HALF_UP);

        int currentIndex = findCurrentAttemptIndex(current, effectiveAttempts);
        ExamResult previous = currentIndex > 0 ? effectiveAttempts.get(currentIndex - 1) : null;
        Double currentDelta = previous == null ? null : scorePercent(current) - scorePercent(previous);

        List<GetStudentFeedbackResponse.AttemptPoint> attemptPoints = effectiveAttempts.stream()
                .map(item -> new GetStudentFeedbackResponse.AttemptPoint(
                        item.getId(),
                        item.getAttemptNumber(),
                        valueOrZero(item.getTotalScore()),
                        valueOrZero(item.getMaxScore()),
                        item.getSubmittedAt(),
                        item.getStatus()))
                .toList();

        return new GetStudentFeedbackResponse.ProgressSummary(
                effectiveAttempts.size(),
                valueOrZero(first.getTotalScore()),
                valueOrZero(current.getTotalScore()),
                valueOrZero(best.getTotalScore()),
                average,
                scorePercent(current) - scorePercent(first),
                currentDelta,
                attemptPoints);
    }

    private GetStudentFeedbackResponse.QuestionFeedback buildQuestionFeedback(QuestionContext context) {
        ExamSubmission submission = context.submission();
        BigDecimal scoreEarned = submission != null ? valueOrZero(submission.getScoreEarned()) : BigDecimal.ZERO;
        BigDecimal maxPoints = valueOrZero(context.question().getPoints());
        List<GradingTraceItem> traceItems = context.trace() == null || context.trace().items() == null
                ? List.of()
                : context.trace().items();

        List<GetStudentFeedbackResponse.TraceEvidence> evidence = traceItems.stream()
                .filter(this::isNegativeTrace)
                .limit(MAX_EVIDENCE_ITEMS)
                .map(this::toEvidence)
                .toList();

        List<String> mistakes = evidence.stream()
                .map(this::summarizeEvidence)
                .filter(text -> !text.isBlank())
                .distinct()
                .limit(4)
                .toList();

        String questionType = context.question().getQuestionType() != null
                ? context.question().getQuestionType().name()
                : "UNKNOWN";

        return new GetStudentFeedbackResponse.QuestionFeedback(
                context.question().getId(),
                context.orderIndex(),
                questionType,
                scoreEarned,
                maxPoints,
                buildQuestionDiagnosis(context, scoreEarned, maxPoints, mistakes),
                mistakes,
                buildQuestionAdvice(questionType, mistakes, submission),
                evidence);
    }

    private String buildQuestionDiagnosis(
            QuestionContext context,
            BigDecimal scoreEarned,
            BigDecimal maxPoints,
            List<String> mistakes) {
        ExamSubmission submission = context.submission();
        if (submission != null && hasText(submission.getErrorMessage())) {
            return "Câu này chưa thực thi đúng do lỗi SQL hoặc lỗi runtime: " + cleanText(submission.getErrorMessage());
        }
        if (maxPoints.compareTo(BigDecimal.ZERO) > 0 && scoreEarned.compareTo(maxPoints) >= 0) {
            return "Câu này đạt yêu cầu chấm điểm hiện tại.";
        }
        if (!mistakes.isEmpty()) {
            return "Câu này mất điểm chủ yếu vì " + lowerFirst(mistakes.get(0));
        }
        return "Câu này chưa đạt đủ điểm; cần đối chiếu lại yêu cầu đề bài, dữ liệu trả về và các điều kiện chấm.";
    }

    private List<String> buildQuestionAdvice(String questionType, List<String> mistakes, ExamSubmission submission) {
        List<String> advice = new ArrayList<>();
        if (submission != null && hasText(submission.getErrorMessage())) {
            advice.add("Chạy lại câu SQL với từng khối nhỏ để tìm đúng vị trí lỗi trước khi ghép thành lời giải cuối.");
        }

        switch (questionType) {
            case "SELECT_QUERY" -> {
                advice.add("Ôn lại cách chọn cột, JOIN, WHERE, GROUP BY/HAVING và ORDER BY theo đúng yêu cầu đề.");
                advice.add("Tự so sánh kết quả theo số dòng, số cột, giá trị từng ô và thứ tự trả về.");
            }
            case "CREATE_TABLE" -> {
                advice.add("Rà lại tên bảng/cột, kiểu dữ liệu, khóa chính, khóa ngoại và ràng buộc NULL/UNIQUE/CHECK.");
                advice.add("Tập viết DDL từ đặc tả trước, sau đó kiểm tra metadata thay vì chỉ nhìn câu lệnh chạy được.");
            }
            case "INSERT_DATA" -> {
                advice.add("Kiểm tra đủ dòng dữ liệu bắt buộc, giá trị NULL và thứ tự insert để không vi phạm khóa ngoại.");
            }
            case "FUNCTION", "STORED_PROCEDURE" -> {
                advice.add("Luyện tách logic thành input, xử lý, output và tự tạo test case biên trước khi nộp.");
                advice.add("Đặc biệt kiểm tra nhánh lỗi, dữ liệu không tồn tại và ràng buộc khóa ngoại.");
            }
            case "TRIGGER" -> {
                advice.add("Ôn lại bảng inserted/deleted, thời điểm trigger chạy và các case nhiều dòng trong cùng một statement.");
            }
            default -> advice.add("Đọc lại yêu cầu câu hỏi và tạo vài bộ dữ liệu nhỏ để tự kiểm tra lời giải.");
        }

        if (mistakes.stream().anyMatch(text -> text.toLowerCase().contains("order"))) {
            advice.add("Nếu đề yêu cầu thứ tự, luôn viết ORDER BY rõ ràng thay vì phụ thuộc thứ tự mặc định của DB.");
        }
        return advice.stream().distinct().limit(4).toList();
    }

    private AIService.StudentFeedbackDraft generateAiDraft(
            GetStudentFeedbackResponse fallback,
            List<QuestionContext> questionContexts) {
        try {
            AIService.StudentFeedbackContext context = new AIService.StudentFeedbackContext(
                    fallback.examTitle(),
                    fallback.attemptNumber(),
                    fallback.totalScore().doubleValue(),
                    fallback.maxScore().doubleValue(),
                    fallback.progress().improvementFromFirstPercent(),
                    fallback.progress().currentAttemptDeltaPercent(),
                    fallback.progress().attempts().stream()
                            .map(attempt -> new AIService.StudentFeedbackAttempt(
                                    attempt.attemptNumber(),
                                    attempt.totalScore().doubleValue(),
                                    attempt.maxScore().doubleValue(),
                                    attempt.submittedAt() != null ? attempt.submittedAt().toString() : "",
                                    attempt.status() != null ? attempt.status().name() : "UNKNOWN"))
                            .toList(),
                    questionContexts.stream()
                            .map(this::toAiQuestion)
                            .toList());

            return aiService.generateStudentFeedback(context);
        } catch (Exception e) {
            log.warn("Cannot build AI feedback context: {}", e.getMessage());
            return null;
        }
    }

    private AIService.StudentFeedbackQuestion toAiQuestion(QuestionContext context) {
        ExamQuestion question = context.question();
        ExamSubmission submission = context.submission();
        BigDecimal scoreEarned = submission != null ? valueOrZero(submission.getScoreEarned()) : BigDecimal.ZERO;
        List<String> traceMessages = context.trace() == null || context.trace().items() == null
                ? List.of()
                : context.trace().items().stream()
                        .filter(item -> !GradingTraceItem.STATUS_PASS.equals(item.status()))
                        .map(this::summarizeTraceForAi)
                        .filter(text -> !text.isBlank())
                        .distinct()
                        .limit(MAX_TRACE_MESSAGES_FOR_AI)
                        .toList();

        return new AIService.StudentFeedbackQuestion(
                question.getId(),
                context.orderIndex(),
                question.getQuestionType() != null ? question.getQuestionType().name() : "UNKNOWN",
                truncate(stripHtml(question.getContent()), 1200),
                truncate(submission != null ? submission.getStudentQuery() : "", 1200),
                truncate(question.getCorrectQuery(), 1200),
                scoreEarned.doubleValue(),
                valueOrZero(question.getPoints()).doubleValue(),
                submission != null ? truncate(cleanText(submission.getErrorMessage()), 800) : null,
                traceMessages);
    }

    private GetStudentFeedbackResponse mergeAiDraft(
            GetStudentFeedbackResponse fallback,
            AIService.StudentFeedbackDraft draft) {
        Map<Long, AIService.StudentQuestionFeedbackDraft> aiByQuestionId = draft.questionFeedbacks() == null
                ? Map.of()
                : draft.questionFeedbacks().stream()
                        .filter(item -> item.questionId() != null)
                        .collect(Collectors.toMap(
                                AIService.StudentQuestionFeedbackDraft::questionId,
                                item -> item,
                                (left, ignored) -> left,
                                LinkedHashMap::new));

        List<GetStudentFeedbackResponse.QuestionFeedback> mergedQuestions = fallback.questionFeedbacks().stream()
                .map(question -> mergeQuestionFeedback(question, aiByQuestionId.get(question.questionId())))
                .toList();

        return new GetStudentFeedbackResponse(
                fallback.resultId(),
                fallback.examId(),
                fallback.examTitle(),
                fallback.attemptNumber(),
                fallback.totalScore(),
                fallback.maxScore(),
                fallback.submittedAt(),
                true,
                TimeUtils.now(),
                fallback.progress(),
                chooseText(draft.overallFeedback(), fallback.overallFeedback()),
                chooseText(draft.progressFeedback(), fallback.progressFeedback()),
                chooseList(draft.strengths(), fallback.strengths()),
                chooseList(draft.weaknesses(), fallback.weaknesses()),
                chooseList(draft.studyAdvice(), fallback.studyAdvice()),
                mergedQuestions);
    }

    private GetStudentFeedbackResponse.QuestionFeedback mergeQuestionFeedback(
            GetStudentFeedbackResponse.QuestionFeedback fallback,
            AIService.StudentQuestionFeedbackDraft draft) {
        if (draft == null) {
            return fallback;
        }
        return new GetStudentFeedbackResponse.QuestionFeedback(
                fallback.questionId(),
                fallback.orderIndex(),
                fallback.questionType(),
                fallback.scoreEarned(),
                fallback.maxPoints(),
                chooseText(draft.diagnosis(), fallback.diagnosis()),
                chooseList(draft.mistakes(), fallback.mistakes()),
                chooseList(draft.advice(), fallback.advice()),
                fallback.evidence());
    }

    private List<String> buildStrengths(
            ExamResult result,
            GetStudentFeedbackResponse.ProgressSummary progress,
            List<GetStudentFeedbackResponse.QuestionFeedback> questionFeedbacks) {
        List<String> strengths = new ArrayList<>();
        double percent = scorePercent(result);
        if (percent >= 70) {
            strengths.add("Nắm được phần lớn yêu cầu của bài thi, tổng điểm đang ở mức khá trở lên.");
        }
        if (progress.improvementFromFirstPercent() > 0.5d) {
            strengths.add("Có tiến bộ so với lần thi đầu, điểm hiện tại tăng khoảng "
                    + formatPercent(progress.improvementFromFirstPercent()) + ".");
        }
        long fullScoreCount = questionFeedbacks.stream()
                .filter(question -> question.maxPoints().compareTo(BigDecimal.ZERO) > 0
                        && question.scoreEarned().compareTo(question.maxPoints()) >= 0)
                .count();
        if (fullScoreCount > 0) {
            strengths.add("Có " + fullScoreCount + " câu đạt trọn điểm, nên xem lại cách làm ở các câu này để giữ nhịp giải.");
        }
        if (strengths.isEmpty()) {
            strengths.add("Đã hoàn thành bài và có dữ liệu cụ thể để đối chiếu từng lỗi sau khi chấm.");
        }
        return strengths;
    }

    private List<String> buildWeaknesses(List<GetStudentFeedbackResponse.QuestionFeedback> questionFeedbacks) {
        List<String> weaknesses = questionFeedbacks.stream()
                .filter(question -> question.scoreEarned().compareTo(question.maxPoints()) < 0)
                .flatMap(question -> question.mistakes().stream())
                .filter(text -> !text.isBlank())
                .distinct()
                .limit(4)
                .collect(Collectors.toCollection(ArrayList::new));
        if (weaknesses.isEmpty()) {
            weaknesses.add("Chưa có lỗi lớn trong trace chấm điểm, nhưng vẫn nên tự kiểm tra lại với dữ liệu biên.");
        }
        return weaknesses;
    }

    private List<String> buildStudyAdvice(
            List<GetStudentFeedbackResponse.QuestionFeedback> questionFeedbacks,
            GetStudentFeedbackResponse.ProgressSummary progress) {
        List<String> advice = new ArrayList<>();
        questionFeedbacks.stream()
                .filter(question -> question.scoreEarned().compareTo(question.maxPoints()) < 0)
                .flatMap(question -> question.advice().stream())
                .filter(text -> !text.isBlank())
                .distinct()
                .limit(5)
                .forEach(advice::add);
        if (progress.currentAttemptDeltaPercent() != null && progress.currentAttemptDeltaPercent() < 0) {
            advice.add("So sánh lại lần thi trước với lần hiện tại để tìm nhóm câu bị giảm điểm, ưu tiên sửa nhóm lỗi lặp lại.");
        }
        if (advice.isEmpty()) {
            advice.add("Tiếp tục luyện bằng cách tự tạo thêm test case biên và đối chiếu expected/actual sau mỗi lần chạy.");
        }
        return advice;
    }

    private String buildOverallFeedback(ExamResult result) {
        double percent = scorePercent(result);
        if (percent >= 85) {
            return "Bài làm đang ở mức tốt. Nên tập trung giữ độ chính xác ở các case biên và trình bày SQL ổn định hơn.";
        }
        if (percent >= 70) {
            return "Bài làm đạt mức khá, nhưng vẫn còn một số điểm mất ở chi tiết chấm. Cần đọc kỹ trace từng câu để tránh lặp lại lỗi nhỏ.";
        }
        if (percent >= 50) {
            return "Bài làm đã có nền tảng nhưng chưa ổn định. Nên ưu tiên sửa các lỗi bị trừ điểm rõ ràng trước khi luyện câu khó hơn.";
        }
        return "Bài làm còn nhiều phần chưa đạt. Nên ôn lại từng dạng câu và luyện lại với test case nhỏ để kiểm soát lỗi thực thi và lỗi logic.";
    }

    private String buildProgressFeedback(GetStudentFeedbackResponse.ProgressSummary progress) {
        if (progress.attemptCount() <= 1) {
            return "Đây là lần thi đầu tiên của bài này nên chưa đủ dữ liệu để kết luận xu hướng tiến bộ.";
        }
        double improvement = progress.improvementFromFirstPercent();
        if (improvement > 0.5d) {
            return "So với lần đầu, điểm hiện tại tăng khoảng " + formatPercent(improvement)
                    + ". Hướng ôn tập đang có tác dụng, nên tiếp tục tập trung vào các lỗi còn lặp lại.";
        }
        if (improvement < -0.5d) {
            return "Điểm hiện tại giảm khoảng " + formatPercent(Math.abs(improvement))
                    + " so với lần đầu. Nên xem lại chiến lược làm bài và kiểm tra kỹ các lỗi cơ bản trước khi nộp.";
        }
        return "Điểm hiện tại gần như không thay đổi so với lần đầu. Cần đổi cách ôn: phân loại lỗi theo từng dạng câu và luyện từng nhóm riêng.";
    }

    private GetStudentFeedbackResponse.TraceEvidence toEvidence(GradingTraceItem item) {
        return new GetStudentFeedbackResponse.TraceEvidence(
                item.kind(),
                item.status(),
                cleanText(item.label()),
                cleanText(item.message()),
                item.deductedPoints(),
                cleanText(item.expected()),
                cleanText(item.actual()));
    }

    private String summarizeEvidence(GetStudentFeedbackResponse.TraceEvidence evidence) {
        if (hasText(evidence.message())) {
            return evidence.message();
        }
        if (hasText(evidence.label())) {
            return evidence.label();
        }
        if (hasText(evidence.expected()) || hasText(evidence.actual())) {
            return "Expected/actual chưa khớp với yêu cầu chấm.";
        }
        return "";
    }

    private String summarizeTraceForAi(GradingTraceItem item) {
        List<String> parts = new ArrayList<>();
        if (hasText(item.kind())) {
            parts.add(item.kind());
        }
        if (hasText(item.status())) {
            parts.add(item.status());
        }
        if (hasText(item.label())) {
            parts.add(cleanText(item.label()));
        }
        if (hasText(item.message())) {
            parts.add(cleanText(item.message()));
        }
        if (item.deductedPoints() != null) {
            parts.add("deducted=" + formatScore(item.deductedPoints()));
        }
        return String.join(" | ", parts);
    }

    private boolean isNegativeTrace(GradingTraceItem item) {
        return item != null
                && (GradingTraceItem.STATUS_FAIL.equals(item.status())
                || GradingTraceItem.STATUS_WARN.equals(item.status()));
    }

    private GradingTrace parseTrace(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, GradingTrace.class);
        } catch (Exception e) {
            log.warn("Cannot parse grading trace for feedback: {}", e.getMessage());
            return null;
        }
    }

    private int findCurrentAttemptIndex(ExamResult current, List<ExamResult> attempts) {
        for (int i = 0; i < attempts.size(); i++) {
            ExamResult attempt = attempts.get(i);
            if (Objects.equals(attempt.getId(), current.getId())) {
                return i;
            }
        }
        for (int i = 0; i < attempts.size(); i++) {
            if (attempts.get(i).getAttemptNumber() == current.getAttemptNumber()) {
                return i;
            }
        }
        return attempts.size() - 1;
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private double scorePercent(ExamResult result) {
        BigDecimal maxScore = valueOrZero(result.getMaxScore());
        if (maxScore.compareTo(BigDecimal.ZERO) <= 0) {
            return 0d;
        }
        return valueOrZero(result.getTotalScore())
                .multiply(BigDecimal.valueOf(100))
                .divide(maxScore, 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String chooseText(String candidate, String fallback) {
        return hasText(candidate) ? candidate.trim() : fallback;
    }

    private List<String> chooseList(List<String> candidate, List<String> fallback) {
        if (candidate == null || candidate.isEmpty()) {
            return fallback;
        }
        List<String> cleaned = candidate.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String cleanText(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String lowerFirst(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.substring(0, 1).toLowerCase() + value.substring(1);
    }

    private String stripHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String formatPercent(double value) {
        return Math.round(value) + "%";
    }

    private String formatScore(BigDecimal value) {
        return valueOrZero(value).stripTrailingZeros().toPlainString();
    }

    private record QuestionContext(
            ExamQuestion question,
            ExamSubmission submission,
            GradingTrace trace,
            int orderIndex) {}
}
