package graduation_project_be.infrastructure.configurations;

import graduation_project_be.shared.utils.TimeUtils;
import graduation_project_be.application.port.repositories.ExamDraftRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.services.ExamSessionService;
import graduation_project_be.application.usecases.SubmitExamUsecase;
import graduation_project_be.application.usecases.request.SubmitExamRequest;
import graduation_project_be.application.usecases.request.SubmitExamRequest.AnswerItem;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamDraft;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class AutoSubmitWorkerConfiguration {

    // Khoảng thời gian dung sai bổ sung: 40 giây (sau 40 giây kể từ khi kết thúc mà FE không nộp thì BE sẽ nộp hộ)
    private static final long AUTO_SUBMIT_GRACE_SECONDS = 40;

    private final ExamDraftRepository examDraftRepository;
    private final ExamRepository examRepository;
    private final ExamSessionService examSessionService;
    private final SubmitExamUsecase submitExamUsecase;

    @Value("${exam.auto-submit-worker.enabled:true}")
    private boolean autoSubmitWorkerEnabled;

    /**
     * Chạy định kỳ mỗi 60 giây (60000ms).
     * Mục đích: Quét tất cả các bản nháp đang tồn tại. Nếu phát hiện version `ExamSession` của user
     * đã quá hạn so với thời điểm kết thúc quy định (kèm theo thời gian trễ + thời gian cho phép làm thêm),
     * hệ thống sẽ tự động fetch bản nháp và invoke luồng chấm bài bình thường thông qua
     * `submitExamUsecase.executeAsSystem()`.
     */
    @Scheduled(fixedRate = 60000)
    public void sweepExpiredExamsAndAutoSubmit() {
        if (!autoSubmitWorkerEnabled) {
            return;
        }

        try {
            // Lấy toàn bộ bản nháp đang tồn tại. Số lượng bản nháp sẽ luôn bằng chính xác
            // số sinh viên đang mở bài thi và làm (nên quét list này sẽ rất tối ưu).
            List<ExamDraft> activeDrafts = examDraftRepository.findAll();

            for (ExamDraft draft : activeDrafts) {
                Long examId = draft.getExamId();
                Long studentId = draft.getStudentId();

                Optional<LocalDateTime> startTimeOpt = examSessionService.getExamStartTime(examId, studentId);
                if (startTimeOpt.isEmpty()) {
                    // Start time không tồn tại, có thể do session đã xoá nhưng draft bị lag chưa xoá. 
                    // Hoặc đơn giản là chưa Start. Bỏ qua.
                    continue;
                }

                Exam exam = examRepository.findByIdAndIsPublished(examId, true).orElse(null);
                if (exam == null) {
                    continue; // Kì thi đã biến mất hoặc bị hủy
                }

                LocalDateTime examStartedAt = startTimeOpt.get();
                LocalDateTime examDeadline = examStartedAt.plusMinutes(exam.getDurationMinutes());

                if (exam.getEndTime() != null && exam.getEndTime().isBefore(examDeadline)) {
                    examDeadline = exam.getEndTime();
                }

                long secondsOverdue = Duration.between(examDeadline, TimeUtils.now()).getSeconds();

                boolean allowOvertime = exam.getSettings() != null
                        && Boolean.TRUE.equals(exam.getSettings().getAllowOvertime());
                int lateThresholdMinutes = exam.getLateThreshold() != null ? exam.getLateThreshold() : 0;

                long effectiveGraceSeconds = AUTO_SUBMIT_GRACE_SECONDS;
                if (allowOvertime && lateThresholdMinutes > 0) {
                    // Nếu thi cho phép trễ, cộng thêm block trễ vào thời hạn đợi auto-submit.
                    effectiveGraceSeconds += (long) lateThresholdMinutes * 60;
                }

                // NẾU SINH VIÊN QUÁ HẠN > SỐ GIÂY DUNG SAI => CHẮC CHẮN MẤT MẠNG VÀ FE KHÔNG THỂ BẮN API
                // => SERVER SẼ ĐỨNG RA NỘP HỘ BẢN NHÁP VÀ CHUYỂN QUA GRADING
                if (secondsOverdue > effectiveGraceSeconds) {
                    log.warn("System Auto-submit: Exam={}, Student={} overdue by {}s. Triggering automatic draft grade ingestion...", 
                            examId, studentId, secondsOverdue);
                            
                    try {
                        List<AnswerItem> answers = draft.getAnswers() == null ? List.of() : draft.getAnswers().stream()
                                .map(da -> new AnswerItem(da.getQuestionId(), da.getContent() != null ? da.getContent() : ""))
                                .collect(Collectors.toList());

                        SubmitExamRequest request = new SubmitExamRequest(examId, answers, null, null);
                        
                        // isAutoSubmit = true => Bỏ qua lỗi Exception OverdueTime do SubmitExamUsecase bắn ra chặn lại. 
                        submitExamUsecase.executeAsSystem(request, studentId, true);
                        
                        log.info("Successfully AUTO-SUBMITTED expired Exam={} for Student={}", examId, studentId);
                    } catch (Exception e) {
                        log.error("Failed to AUTO-SUBMIT expired Exam={} for Student={}: {}", examId, studentId, e.getMessage(), e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error during auto-submit expired exams cron sweeper execution", e);
        }
    }
}
