package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.exceptions.ResourceNotFoundException;
import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassEnrollmentRepository;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ExamQuestionRepository;
import graduation_project_be.application.port.repositories.ExamRepository;
import graduation_project_be.application.port.repositories.ExamResultRepository;
import graduation_project_be.application.port.repositories.ExamSubmissionRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.port.services.GradingQueueService;
import graduation_project_be.application.usecases.request.MoodleSqlImportRequest;
import graduation_project_be.application.usecases.response.MoodleSqlImportConfirmResponse;
import graduation_project_be.application.usecases.response.MoodleSqlImportPreviewResponse;
import graduation_project_be.domain.models.ClassEnrollment;
import graduation_project_be.domain.models.Exam;
import graduation_project_be.domain.models.ExamQuestion;
import graduation_project_be.domain.models.ExamResult;
import graduation_project_be.domain.models.ExamSubmission;
import graduation_project_be.domain.models.User;
import graduation_project_be.domain.models.enums.GradingStatus;
import graduation_project_be.domain.models.enums.SubmissionStatus;
import graduation_project_be.shared.utils.TimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class MoodleSqlImportUsecase {

    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final Pattern HEADER_EMAIL_PATTERN = Pattern.compile(
            "(?im)^\\s*--\\s*(?:STUDENT_EMAIL|EMAIL)\\s*:\\s*([^\\s]+)\\s*$");
    private static final Pattern HEADER_CODE_PATTERN = Pattern.compile(
            "(?im)^\\s*--\\s*(?:STUDENT_CODE|STUDENT_ID|MSSV)\\s*:\\s*([A-Za-z0-9._-]+)\\s*$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern STUDENT_CODE_PATTERN = Pattern.compile("(?<!\\d)(\\d{6,12})(?!\\d)");
    private static final Pattern QUESTION_MARKER_PATTERN = Pattern.compile(
            "(?im)^\\s*--\\s*QUESTION_(ID|ORDER)\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern END_QUESTION_PATTERN = Pattern.compile(
            "(?im)^\\s*--\\s*END_QUESTION\\s*$");

    private final ExamRepository examRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final ExamSubmissionRepository examSubmissionRepository;
    private final ExamResultRepository examResultRepository;
    private final ClassEnrollmentRepository classEnrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final GradingQueueService gradingQueueService;

    public MoodleSqlImportPreviewResponse preview(MoodleSqlImportRequest request) {
        ImportContext context = loadContext(request.examId());
        ImportParseResult parseResult = parseFiles(context, request.files());
        return toPreviewResponse(context, parseResult);
    }

    @Transactional
    public MoodleSqlImportConfirmResponse confirm(MoodleSqlImportRequest request) {
        ImportContext context = loadContext(request.examId());
        ImportParseResult parseResult = parseFiles(context, request.files());
        MoodleSqlImportPreviewResponse preview = toPreviewResponse(context, parseResult);

        if (!preview.readyToImport()) {
            throw new BadRequestException("Vẫn còn file SQL chưa hợp lệ. Vui lòng sửa lỗi trong bước kiểm tra trước khi import.");
        }

        List<GradingQueueService.GradingJob> jobs = new ArrayList<>();
        List<MoodleSqlImportConfirmResponse.ImportedFile> importedFiles = new ArrayList<>();
        BigDecimal maxScore = context.questions().stream()
                .map(question -> question.getPoints() != null ? question.getPoints() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (ParsedSqlFile parsedFile : parseResult.files()) {
            User student = parsedFile.student();
            if (student == null) {
                throw new BadRequestException("File " + parsedFile.fileName() + " chưa map được sinh viên.");
            }

            long previousAttempts = examResultRepository.countByExamIdAndStudentId(
                    context.exam().getId(), student.getId());
            int attemptNumber = (int) previousAttempts + 1;
            String schemaName = String.format(
                    "exam_%d_student_%d_att_%d", context.exam().getId(), student.getId(), attemptNumber);
            var submittedAt = TimeUtils.now();

            List<ExamSubmission> submissions = context.questions().stream()
                    .map(question -> ExamSubmission.builder()
                            .examId(context.exam().getId())
                            .questionId(question.getId())
                            .studentId(student.getId())
                            .attemptNumber(attemptNumber)
                            .assignedSchemaName(schemaName)
                            .studentQuery(parsedFile.answersByQuestionId().getOrDefault(question.getId(), ""))
                            .isCorrect(null)
                            .scoreEarned(null)
                            .errorMessage(null)
                            .executionTimeMs(null)
                            .status(SubmissionStatus.PENDING)
                            .submittedAt(submittedAt)
                            .build())
                    .toList();
            examSubmissionRepository.saveAll(submissions);

            ExamResult result = ExamResult.builder()
                    .examId(context.exam().getId())
                    .studentId(student.getId())
                    .attemptNumber(attemptNumber)
                    .totalScore(BigDecimal.ZERO)
                    .maxScore(maxScore)
                    .totalQuestions(context.questions().size())
                    .correctCount(0)
                    .lateDurationSeconds(0)
                    .submittedAt(submittedAt)
                    .status(GradingStatus.PENDING)
                    .build();
            ExamResult savedResult = examResultRepository.save(result);

            jobs.add(new GradingQueueService.GradingJob(
                    context.exam().getId(), student.getId(), attemptNumber, 0));
            importedFiles.add(new MoodleSqlImportConfirmResponse.ImportedFile(
                    parsedFile.fileName(),
                    student.getId(),
                    student.getEmail(),
                    student.getFullName(),
                    savedResult.getId(),
                    attemptNumber,
                    parsedFile.answersByQuestionId().size()));
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (GradingQueueService.GradingJob job : jobs) {
                    gradingQueueService.enqueue(job.examId(), job.studentId(), job.attemptNumber());
                }
                log.info("Moodle SQL import: enqueued {} grading jobs for exam={}", jobs.size(), context.exam().getId());
            }
        });

        return new MoodleSqlImportConfirmResponse(
                context.exam().getId(),
                importedFiles.size(),
                jobs.size(),
                importedFiles,
                "Đã import " + importedFiles.size() + " bài làm SQL và đưa vào hàng đợi chấm.");
    }

    private ImportContext loadContext(Long examId) {
        if (examId == null) {
            throw new BadRequestException("Exam id is required");
        }

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam", "id", examId));
        if (!Boolean.TRUE.equals(exam.getIsPublished())) {
            throw new BadRequestException("Bài thi phải được publish trước khi import bài làm SQL.");
        }

        Long teacherId = currentUserService.getCurrentUserId();
        boolean hasAccess = Objects.equals(exam.getCreatorId(), teacherId)
                || classRepository.existsTeacherAccess(exam.getClassId(), teacherId);
        if (!hasAccess) {
            throw new UnauthorizedException("Bạn không có quyền import bài làm cho bài thi này.");
        }

        List<ExamQuestion> questions = examQuestionRepository.findByExamId(examId).stream()
                .sorted(Comparator.comparingInt(this::safeOrderIndex))
                .toList();
        if (questions.isEmpty()) {
            throw new BadRequestException("Bài thi chưa có câu hỏi để chấm.");
        }

        List<ClassEnrollment> enrollments = classEnrollmentRepository.findByClassId(exam.getClassId());
        List<Long> studentIds = enrollments.stream()
                .map(ClassEnrollment::getStudentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<User> students = studentIds.isEmpty() ? List.of() : userRepository.findAllById(studentIds);
        if (students.isEmpty()) {
            throw new BadRequestException("Lớp của bài thi chưa có sinh viên để map file SQL.");
        }

        Map<Long, ExamQuestion> questionById = questions.stream()
                .collect(Collectors.toMap(ExamQuestion::getId, question -> question));
        Map<Integer, ExamQuestion> questionByOrder = new HashMap<>();
        for (ExamQuestion question : questions) {
            if (question.getOrderIndex() != null) {
                questionByOrder.putIfAbsent(question.getOrderIndex(), question);
            }
        }

        Map<String, List<User>> studentsByEmail = new HashMap<>();
        Map<String, List<User>> studentsByCode = new HashMap<>();
        for (User student : students) {
            String email = normalize(student.getEmail());
            if (email != null) {
                studentsByEmail.computeIfAbsent(email, ignored -> new ArrayList<>()).add(student);
                String code = studentCodeFromEmail(email);
                if (code != null) {
                    studentsByCode.computeIfAbsent(code, ignored -> new ArrayList<>()).add(student);
                }
            }
        }

        return new ImportContext(exam, questions, questionById, questionByOrder, studentsByEmail, studentsByCode);
    }

    private ImportParseResult parseFiles(
            ImportContext context,
            List<MoodleSqlImportRequest.UploadedSqlFile> uploadedFiles) {
        if (uploadedFiles == null || uploadedFiles.isEmpty()) {
            throw new BadRequestException("Cần upload ít nhất một file .sql.");
        }

        List<ParsedSqlFile> parsedFiles = uploadedFiles.stream()
                .map(file -> parseFile(context, file))
                .toList();

        Map<Long, List<ParsedSqlFile>> filesByStudent = parsedFiles.stream()
                .filter(file -> file.student() != null)
                .collect(Collectors.groupingBy(file -> file.student().getId()));

        for (List<ParsedSqlFile> duplicateFiles : filesByStudent.values()) {
            if (duplicateFiles.size() <= 1) {
                continue;
            }
            String fileNames = duplicateFiles.stream()
                    .map(ParsedSqlFile::fileName)
                    .collect(Collectors.joining(", "));
            for (ParsedSqlFile duplicateFile : duplicateFiles) {
                duplicateFile.errors().add(
                        "Nhiều file cùng map vào sinh viên này trong một batch: " + fileNames + ".");
            }
        }

        return new ImportParseResult(parsedFiles);
    }

    private ParsedSqlFile parseFile(
            ImportContext context,
            MoodleSqlImportRequest.UploadedSqlFile uploadedFile) {
        String fileName = safeFileName(uploadedFile.fileName());
        String content = normalizeContent(uploadedFile.content());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<Long, String> answersByQuestionId = new LinkedHashMap<>();
        Map<Long, String> markerByQuestionId = new HashMap<>();

        validateFileBasics(uploadedFile, fileName, content, errors);

        Identifier identifier = detectIdentifier(fileName, content);
        if (identifier == null) {
            errors.add("Không tìm thấy MSSV/email trong header hoặc tên file.");
        }

        User student = identifier != null ? resolveStudent(context, identifier, errors) : null;
        parseAnswers(context, content, answersByQuestionId, markerByQuestionId, errors, warnings);

        int missingQuestions = Math.max(0, context.questions().size() - answersByQuestionId.size());
        if (missingQuestions > 0) {
            warnings.add("Thiếu câu trả lời cho " + missingQuestions + " câu; các câu này sẽ được chấm như bài trống.");
        }

        return new ParsedSqlFile(
                fileName,
                identifier != null ? identifier.displayValue() : null,
                student,
                answersByQuestionId,
                markerByQuestionId,
                errors,
                warnings);
    }

    private void validateFileBasics(
            MoodleSqlImportRequest.UploadedSqlFile uploadedFile,
            String fileName,
            String content,
            List<String> errors) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            errors.add("File phải có đuôi .sql.");
        }
        if (uploadedFile.sizeBytes() <= 0 || content.isBlank()) {
            errors.add("File SQL đang trống.");
        }
        if (uploadedFile.sizeBytes() > MAX_FILE_SIZE_BYTES) {
            errors.add("File vượt quá giới hạn 5MB.");
        }
    }

    private Identifier detectIdentifier(String fileName, String content) {
        Optional<Identifier> headerEmail = firstMatch(HEADER_EMAIL_PATTERN, content)
                .map(value -> Identifier.email(value, "header STUDENT_EMAIL"));
        if (headerEmail.isPresent()) {
            return headerEmail.get();
        }

        Optional<Identifier> headerCode = firstMatch(HEADER_CODE_PATTERN, content)
                .map(value -> Identifier.code(value, "header STUDENT_CODE/MSSV"));
        if (headerCode.isPresent()) {
            return headerCode.get();
        }

        Optional<Identifier> filenameEmail = firstMatch(EMAIL_PATTERN, fileName)
                .map(value -> Identifier.email(value, "tên file"));
        if (filenameEmail.isPresent()) {
            return filenameEmail.get();
        }

        return firstMatch(STUDENT_CODE_PATTERN, fileName)
                .map(value -> Identifier.code(value, "tên file"))
                .orElse(null);
    }

    private Optional<String> firstMatch(Pattern pattern, String value) {
        if (value == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1)).map(String::trim).filter(s -> !s.isBlank());
    }

    private User resolveStudent(ImportContext context, Identifier identifier, List<String> errors) {
        List<User> matches = identifier.email()
                ? findByEmailOrEmailCode(context, identifier.normalizedValue())
                : context.studentsByCode().getOrDefault(identifier.normalizedValue(), List.of());

        if (matches.isEmpty()) {
            errors.add("Không tìm thấy sinh viên trong lớp với " + identifier.displayValue() + ".");
            return null;
        }
        if (matches.size() > 1) {
            errors.add("Có nhiều sinh viên cùng khớp với " + identifier.displayValue() + ".");
            return null;
        }
        return matches.get(0);
    }

    private List<User> findByEmailOrEmailCode(ImportContext context, String normalizedEmail) {
        List<User> emailMatches = context.studentsByEmail().getOrDefault(normalizedEmail, List.of());
        if (!emailMatches.isEmpty()) {
            return emailMatches;
        }
        String code = studentCodeFromEmail(normalizedEmail);
        if (code == null) {
            return List.of();
        }
        return context.studentsByCode().getOrDefault(code, List.of());
    }

    private void parseAnswers(
            ImportContext context,
            String content,
            Map<Long, String> answersByQuestionId,
            Map<Long, String> markerByQuestionId,
            List<String> errors,
            List<String> warnings) {
        Matcher matcher = QUESTION_MARKER_PATTERN.matcher(content);
        List<QuestionMarker> markers = new ArrayList<>();
        while (matcher.find()) {
            String markerType = matcher.group(1);
            int markerValue = Integer.parseInt(matcher.group(2));
            markers.add(new QuestionMarker(markerType, markerValue, matcher.start(), matcher.end()));
        }

        if (markers.isEmpty()) {
            warnings.add("Không tìm thấy marker QUESTION_ID/QUESTION_ORDER trong file.");
            return;
        }

        for (int i = 0; i < markers.size(); i++) {
            QuestionMarker marker = markers.get(i);
            int blockEnd = i + 1 < markers.size() ? markers.get(i + 1).startOffset() : content.length();
            String block = content.substring(marker.endOffset(), blockEnd);
            String sql = stripEndQuestion(block);

            ExamQuestion question = resolveQuestion(context, marker);
            String markerLabel = "QUESTION_" + marker.type() + ": " + marker.value();
            if (question == null) {
                errors.add("Marker " + markerLabel + " không khớp câu hỏi nào trong bài thi.");
                continue;
            }
            if (answersByQuestionId.containsKey(question.getId())) {
                errors.add("Câu " + question.getOrderIndex() + " bị khai báo nhiều lần trong file.");
                continue;
            }
            if (sql.isBlank()) {
                warnings.add("Câu " + question.getOrderIndex() + " có marker nhưng nội dung SQL trống.");
            }
            answersByQuestionId.put(question.getId(), sql);
            markerByQuestionId.put(question.getId(), markerLabel);
        }
    }

    private String stripEndQuestion(String block) {
        Matcher endMatcher = END_QUESTION_PATTERN.matcher(block);
        String body = endMatcher.find() ? block.substring(0, endMatcher.start()) : block;
        return body.strip();
    }

    private ExamQuestion resolveQuestion(ImportContext context, QuestionMarker marker) {
        if ("ID".equals(marker.type())) {
            return context.questionById().get((long) marker.value());
        }
        return context.questionByOrder().get(marker.value());
    }

    private MoodleSqlImportPreviewResponse toPreviewResponse(ImportContext context, ImportParseResult parseResult) {
        List<MoodleSqlImportPreviewResponse.FilePreview> files = parseResult.files().stream()
                .map(file -> toFilePreview(context, file))
                .toList();
        int validFiles = (int) files.stream().filter(MoodleSqlImportPreviewResponse.FilePreview::valid).count();
        int invalidFiles = files.size() - validFiles;
        return new MoodleSqlImportPreviewResponse(
                context.exam().getId(),
                context.exam().getTitle(),
                files.size(),
                validFiles,
                invalidFiles,
                context.questions().size(),
                !files.isEmpty() && invalidFiles == 0,
                files);
    }

    private MoodleSqlImportPreviewResponse.FilePreview toFilePreview(
            ImportContext context,
            ParsedSqlFile file) {
        List<MoodleSqlImportPreviewResponse.AnswerPreview> answers = context.questions().stream()
                .map(question -> {
                    String sql = file.answersByQuestionId().get(question.getId());
                    return new MoodleSqlImportPreviewResponse.AnswerPreview(
                            question.getId(),
                            question.getOrderIndex(),
                            question.getQuestionType() != null ? question.getQuestionType().name() : null,
                            sql != null && !sql.isBlank(),
                            sql != null ? sql.length() : 0,
                            file.markerByQuestionId().get(question.getId()));
                })
                .toList();

        int answeredQuestions = (int) answers.stream()
                .filter(MoodleSqlImportPreviewResponse.AnswerPreview::hasAnswer)
                .count();
        int missingQuestions = Math.max(0, context.questions().size() - answeredQuestions);
        User student = file.student();
        return new MoodleSqlImportPreviewResponse.FilePreview(
                file.fileName(),
                file.detectedIdentifier(),
                student != null ? student.getId() : null,
                student != null ? student.getEmail() : null,
                student != null ? student.getFullName() : null,
                file.errors().isEmpty(),
                answeredQuestions,
                missingQuestions,
                answers,
                List.copyOf(file.errors()),
                List.copyOf(file.warnings()));
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        if (content.startsWith("\uFEFF")) {
            return content.substring(1);
        }
        return content;
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unknown.sql";
        }
        return fileName.trim();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String studentCodeFromEmail(String email) {
        String normalized = normalize(email);
        if (normalized == null) {
            return null;
        }
        int atIndex = normalized.indexOf('@');
        if (atIndex <= 0) {
            return null;
        }
        return normalized.substring(0, atIndex);
    }

    private int safeOrderIndex(ExamQuestion question) {
        return question.getOrderIndex() != null ? question.getOrderIndex() : Integer.MAX_VALUE;
    }

    private record ImportContext(
            Exam exam,
            List<ExamQuestion> questions,
            Map<Long, ExamQuestion> questionById,
            Map<Integer, ExamQuestion> questionByOrder,
            Map<String, List<User>> studentsByEmail,
            Map<String, List<User>> studentsByCode) {
    }

    private record ImportParseResult(List<ParsedSqlFile> files) {
    }

    private record ParsedSqlFile(
            String fileName,
            String detectedIdentifier,
            User student,
            Map<Long, String> answersByQuestionId,
            Map<Long, String> markerByQuestionId,
            List<String> errors,
            List<String> warnings) {
    }

    private record QuestionMarker(String type, int value, int startOffset, int endOffset) {
    }

    private record Identifier(String value, String source, boolean email) {
        static Identifier email(String value, String source) {
            return new Identifier(value, source, true);
        }

        static Identifier code(String value, String source) {
            return new Identifier(value, source, false);
        }

        String normalizedValue() {
            return value.trim().toLowerCase(Locale.ROOT);
        }

        String displayValue() {
            return value.trim() + " (" + source + ")";
        }
    }
}
