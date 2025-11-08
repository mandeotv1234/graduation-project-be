package graduation_project_be.infrastructure.errors.exceptions;

import graduation_project_be.application.exceptions.OperationFailedException;

public class UserNotFoundException extends OperationFailedException {
    public UserNotFoundException(String message) {
        super(message, null);
    }
}
