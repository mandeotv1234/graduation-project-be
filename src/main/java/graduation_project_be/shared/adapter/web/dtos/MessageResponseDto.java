package graduation_project_be.shared.adapter.web.dtos;

// This assumes MessageResponse will be a shared concept, maybe defined in a shared usecase or domain level.
// For now, let's remove the dependency and assume a simple record will be passed.
// import graduation_project_be.application.usecases.response.MessageResponse;

public record MessageResponseDto(String message) {
    // public static MessageResponseDto from(MessageResponse out){
    //     return new MessageResponseDto(out.message());
    // }
}
