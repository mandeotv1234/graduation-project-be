package graduation_project_be.domain.exceptions;

public class InvalidProgressException extends RuntimeException {
    public InvalidProgressException(String message) {
        super(message);
    }
}
