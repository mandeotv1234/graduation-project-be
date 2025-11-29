package graduation_project_be.shared.infrastructure.error.exceptions;

import graduation_project_be.shared.application.exception.OperationFailedException;

public class UserNotFoundException extends OperationFailedException {
    public UserNotFoundException(String message) {
        super(message, null);
    }
}
