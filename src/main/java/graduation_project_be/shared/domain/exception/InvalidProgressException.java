package graduation_project_be.shared.domain.exception;

public class InvalidProgressException extends RuntimeException {
    public InvalidProgressException(String message) {
        super(message);
    }
}
