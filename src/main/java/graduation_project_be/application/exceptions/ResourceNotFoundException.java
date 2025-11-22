package graduation_project_be.application.exceptions;

public class ResourceNotFoundException extends ApplicationException {
    public ResourceNotFoundException(String resource, Object field, Object value) {
        super(String.format("%s not found with the given input data %s: '%s'", resource, field.toString(), value.toString()));
    }
}
