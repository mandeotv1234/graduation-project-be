package graduation_project_be.shared.application.exception;

public class OperationFailedException extends ApplicationException {
    public OperationFailedException(String operation, String reason) {
        super(operation + " failed: " + reason);
    }
}
