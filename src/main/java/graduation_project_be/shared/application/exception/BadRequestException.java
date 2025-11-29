package graduation_project_be.shared.application.exception;

public class BadRequestException extends ApplicationException {
    public BadRequestException(String message) {
        super(message);
    }
}

