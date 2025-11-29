package graduation_project_be.shared.domain.enums;

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
        return this.name().toLowerCase();
    }
}
