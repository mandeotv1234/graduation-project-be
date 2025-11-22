package graduation_project_be.application.exceptions;

public class BadRequestException extends ApplicationException {
    public BadRequestException(String message) {
        super(message);
    }
}

