package graduation_project_be.application.exceptions;

public class ConflictException extends ApplicationException {
    public ConflictException(String resource, Object field, Object value) {
        super(String.format("%s already existed with the given input data %s: '%s'", resource, field.toString(), value.toString()));
    }
}

