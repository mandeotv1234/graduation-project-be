package graduation_project_be.application.usecases.support;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.repositories.ExamSpecificationRepository;
import graduation_project_be.domain.models.ExamSettings;
import graduation_project_be.domain.models.SpecDataset;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

public final class ExamSettingsValidator {

    public static final int MAX_EXAM_DURATION_MINUTES = 240;
    public static final int MAX_EXAM_ATTEMPTS = 99;
    public static final int MAX_LATE_THRESHOLD_MINUTES = 240;
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_VIOLATIONS = 100;
    public static final BigDecimal MIN_QUESTION_POINTS = new BigDecimal("0.1");
    public static final BigDecimal MAX_QUESTION_POINTS = BigDecimal.TEN;
    public static final BigDecimal MAX_EXAM_POINTS = BigDecimal.TEN;
    private static final Set<String> SCORE_DISPLAY_MODES =
            Set.of("immediately", "after_closed", "never");
    private static final Set<String> GRADING_METHODS =
            Set.of("highest_score", "latest_score", "average_score");

    private ExamSettingsValidator() {
    }

    public static void validateExamConfiguration(
            String title,
            Integer durationMinutes,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer maxAttempts,
            Integer lateThreshold,
            ExamSettings settings) {
        if (title == null || title.isBlank() || title.trim().length() > MAX_TITLE_LENGTH) {
            throw new BadRequestException("Tiêu đề bài thi phải từ 1 đến 255 ký tự.");
        }
        validateDuration(durationMinutes);
        validateExamTimeWindow(startTime, endTime, durationMinutes);

        if (maxAttempts != null && (maxAttempts < 1 || maxAttempts > MAX_EXAM_ATTEMPTS)) {
            throw new BadRequestException("Số lần làm bài phải từ 1 đến 99.");
        }
        if (lateThreshold != null
                && (lateThreshold < 0 || lateThreshold > MAX_LATE_THRESHOLD_MINUTES)) {
            throw new BadRequestException("Ngưỡng nộp trễ phải từ 0 đến 240 phút.");
        }
        validateSettings(settings);
        if (settings != null
                && Boolean.TRUE.equals(settings.getAllowOvertime())
                && (lateThreshold == null || lateThreshold <= 0)) {
            throw new BadRequestException(
                    "Phải cấu hình ngưỡng nộp trễ lớn hơn 0 khi cho phép nộp trễ.");
        }
    }

    public static void validateDuration(Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes <= 0 || durationMinutes > MAX_EXAM_DURATION_MINUTES) {
            throw new BadRequestException("Thời lượng bài thi phải từ 1 đến 240 phút.");
        }
    }

    public static void validateQuestionPoints(BigDecimal points) {
        if (points == null
                || points.compareTo(MIN_QUESTION_POINTS) < 0
                || points.compareTo(MAX_QUESTION_POINTS) > 0
                || Math.max(0, points.stripTrailingZeros().scale()) > 2) {
            throw new BadRequestException(
                    "Điểm mỗi câu phải từ 0.1 đến 10 và có tối đa 2 chữ số thập phân.");
        }
    }

    public static void validateQuestionMetadata(Integer difficultyLevel, Integer orderIndex) {
        if (difficultyLevel != null && (difficultyLevel < 1 || difficultyLevel > 5)) {
            throw new BadRequestException("Độ khó câu hỏi phải từ 1 đến 5.");
        }
        if (orderIndex != null && orderIndex <= 0) {
            throw new BadRequestException("Thứ tự câu hỏi phải lớn hơn 0.");
        }
    }

    public static void validateTotalPoints(Collection<BigDecimal> points) {
        BigDecimal total = points.stream().filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(MAX_EXAM_POINTS) > 0) {
            throw new BadRequestException("Tổng điểm các câu hỏi không được vượt quá 10 điểm.");
        }
    }

    public static void validateDatabaseInitialization(
            ExamSpecificationRepository examSpecificationRepository,
            Long specificationId,
            ExamSettings settings) {
        boolean shouldLoadDdl = settings != null && Boolean.TRUE.equals(settings.getIsLoadDdl());
        if (specificationId == null || specificationId <= 0L) {
            if (shouldLoadDdl) {
                throw new BadRequestException(
                        "Đề thi đã bật nạp DDL và dataset nhưng chưa chọn đặc tả CSDL.");
            }
            return;
        }

        var specification = examSpecificationRepository.findById(specificationId)
                .orElseThrow(() -> new BadRequestException("Không tìm thấy đặc tả CSDL của đề thi."));

        if (!shouldLoadDdl) {
            return;
        }
        Long seedDatasetId = settings.getSeedDatasetId();
        if (seedDatasetId == null) {
            throw new BadRequestException("Đề thi đã bật nạp DDL và dataset nhưng chưa chọn dataset.");
        }

        if (specification.getDdlScript() == null || specification.getDdlScript().isBlank()) {
            throw new BadRequestException("Đặc tả CSDL đang chọn chưa có DDL script.");
        }

        boolean validDataset = specification.getDatasets() != null
                && specification.getDatasets().stream()
                        .filter(SpecDataset::isActive)
                        .anyMatch(dataset -> seedDatasetId.equals(dataset.getId())
                                && dataset.getDataScript() != null
                                && !dataset.getDataScript().isBlank());

        if (!validDataset) {
            throw new BadRequestException(
                    "Dataset đã chọn không thuộc đặc tả CSDL, đang tắt, hoặc không có data script.");
        }
    }

    public static void validateExamTimeWindow(LocalDateTime startTime, LocalDateTime endTime) {
        validateExamTimeWindow(startTime, endTime, null);
    }

    public static void validateExamTimeWindow(
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer durationMinutes) {
        if (startTime == null && endTime == null) {
            return;
        }
        if (startTime == null || endTime == null) {
            throw new BadRequestException(
                    "Phải nhập đầy đủ cả thời gian bắt đầu và thời gian kết thúc.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new BadRequestException("Thời gian kết thúc phải sau thời gian bắt đầu.");
        }
        if (durationMinutes != null && endTime.isBefore(startTime.plusMinutes(durationMinutes))) {
            throw new BadRequestException(
                    "Khoảng thời gian từ lúc bắt đầu đến lúc kết thúc phải ít nhất bằng thời lượng bài thi.");
        }
    }

    private static void validateSettings(ExamSettings settings) {
        if (settings == null) {
            return;
        }
        if (settings.getScoreDisplayMode() != null
                && !SCORE_DISPLAY_MODES.contains(settings.getScoreDisplayMode())) {
            throw new BadRequestException("Chế độ hiển thị điểm không hợp lệ.");
        }
        if (settings.getGradingMethod() != null
                && !GRADING_METHODS.contains(settings.getGradingMethod())) {
            throw new BadRequestException("Phương thức tính điểm không hợp lệ.");
        }
        Integer maxViolations = settings.getMaxViolations();
        if (maxViolations != null && (maxViolations < 1 || maxViolations > MAX_VIOLATIONS)) {
            throw new BadRequestException("Số lần vi phạm tối đa phải từ 1 đến 100.");
        }
        Integer heartbeatInterval = settings.getHeartbeatIntervalSec();
        if (heartbeatInterval != null && (heartbeatInterval < 3 || heartbeatInterval > 60)) {
            throw new BadRequestException("Chu kỳ heartbeat phải từ 3 đến 60 giây.");
        }
        Integer maxHeartbeatGap = settings.getMaxHeartbeatGapSec();
        if (maxHeartbeatGap != null && (maxHeartbeatGap < 10 || maxHeartbeatGap > 120)) {
            throw new BadRequestException("Khoảng mất heartbeat tối đa phải từ 10 đến 120 giây.");
        }
        if (heartbeatInterval != null
                && maxHeartbeatGap != null
                && maxHeartbeatGap <= heartbeatInterval) {
            throw new BadRequestException(
                    "Khoảng mất heartbeat tối đa phải lớn hơn chu kỳ heartbeat.");
        }
    }
}
