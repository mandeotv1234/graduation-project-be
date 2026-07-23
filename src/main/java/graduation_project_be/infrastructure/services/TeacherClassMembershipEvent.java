package graduation_project_be.infrastructure.services;

public record TeacherClassMembershipEvent(
        Action action,
        Long classId,
        String classCode,
        String semester,
        String recipientEmail,
        String recipientName,
        String actorEmail,
        String actorName) {

    public enum Action {
        ADDED,
        REMOVED
    }
}
