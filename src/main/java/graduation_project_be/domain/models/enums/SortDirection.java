package graduation_project_be.domain.models.enums;

public enum SortDirection {
    ASC, DESC;

    public static SortDirection fromString(String direction) {
        if (direction == null)
            return ASC;

        return direction.equalsIgnoreCase("desc") ? DESC : ASC;
    }
}
