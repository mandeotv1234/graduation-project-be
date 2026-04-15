package graduation_project_be.domain.models.enums;

public enum SortField {
    CREATED_AT, SUBMITTED_AT;

    public static SortField fromString(String field) {
        if (field == null) {
            return SUBMITTED_AT;
        }

        try {
            return SortField.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            return SUBMITTED_AT;
        }
    }

    public String getFieldName() {
        return switch (this) {
            case CREATED_AT -> "createdAt";
            case SUBMITTED_AT -> "submittedAt";
        };
    }
}
