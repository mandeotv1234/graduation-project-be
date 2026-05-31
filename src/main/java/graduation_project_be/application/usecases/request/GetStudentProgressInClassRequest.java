package graduation_project_be.application.usecases.request;

public record GetStudentProgressInClassRequest(
        Long classId,
        Long studentId) {
}
