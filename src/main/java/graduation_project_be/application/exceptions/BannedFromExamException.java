package graduation_project_be.application.exceptions;

public class BannedFromExamException extends RuntimeException {
    public BannedFromExamException(String message) {
        super(message);
    }
}
