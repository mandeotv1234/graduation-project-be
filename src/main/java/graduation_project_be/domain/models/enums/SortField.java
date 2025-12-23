package graduation_project_be.domain.models.enums;

public enum SortField {
    TIME;

    public static SortField fromString(String field) {
        if (field == null) {
            return TIME;
        }

        try {
            return SortField.valueOf(field.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TIME;
        }
    }

    public String getFieldName() {
        return switch (this) {
            case TIME -> "createdAt";
        };
    }
}
