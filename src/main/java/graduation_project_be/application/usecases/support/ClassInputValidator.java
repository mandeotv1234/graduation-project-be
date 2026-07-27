package graduation_project_be.application.usecases.support;

import graduation_project_be.application.exceptions.BadRequestException;

public final class ClassInputValidator {

    public static final int CLASS_CODE_MAX_LENGTH = 20;
    public static final int SEMESTER_MAX_LENGTH = 20;
    public static final String STUDENT_CODE_REGEX = "\\d{8}";

    private ClassInputValidator() {
    }

    public static void validateClassDetails(String classCode, String semester) {
        validateRequiredLength("Mã lớp", classCode, CLASS_CODE_MAX_LENGTH);
        validateRequiredLength("Học kỳ", semester, SEMESTER_MAX_LENGTH);
    }

    public static boolean isValidStudentCode(String studentCode) {
        return studentCode != null && studentCode.matches(STUDENT_CODE_REGEX);
    }

    private static void validateRequiredLength(String fieldName, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(fieldName + " không được để trống");
        }
        if (value.trim().length() > maxLength) {
            throw new BadRequestException(fieldName + " không được vượt quá " + maxLength + " ký tự");
        }
    }
}
