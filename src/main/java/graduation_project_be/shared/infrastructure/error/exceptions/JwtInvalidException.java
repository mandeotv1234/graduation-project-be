package graduation_project_be.shared.infrastructure.error.exceptions;


import graduation_project_be.shared.application.exception.OperationFailedException;

public class JwtInvalidException extends OperationFailedException {
    public JwtInvalidException(String operation, String reason) {
        super(operation, reason);
    }
}