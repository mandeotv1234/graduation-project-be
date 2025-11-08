package graduation_project_be.infrastructure.errors.exceptions;


import graduation_project_be.application.exceptions.OperationFailedException;

public class JwtInvalidException extends OperationFailedException {
    public JwtInvalidException(String operation, String reason) {
        super(operation, reason);
    }
}