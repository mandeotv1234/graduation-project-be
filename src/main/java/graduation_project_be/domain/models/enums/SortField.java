package graduation_project_be.domain.models.enums;

public enum SortField {
    CREATED_AT;

    public static SortField fromString(String field) {
        if (field == null) {
            return CREATED_AT;
        }

        try {
            return SortField.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CREATED_AT;
        }
    }

    public String getFieldName() {
        return switch (this) {
            case CREATED_AT -> "createdAt";
        };
    }
}
