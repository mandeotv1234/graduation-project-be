package graduation_project_be.adapter.web.api.dtos.response;


import java.time.LocalDateTime;
import graduation_project_be.application.codes.Code;

public record ResponseDto(
        Object data,
        Meta meta,
        String code,
        String message
) {

    public ResponseDto(Object data) {
        this(data, new Meta(LocalDateTime.now()), Code.OK.toString(), "OK");
    }

    public static ResponseDto of(Object data) {
        return new ResponseDto(data);
    }

    public static ResponseDto ok(Object data) {
        return new ResponseDto(data);
    }

    public static ResponseDto of(Object data, String code, String message) {
        return new ResponseDto(data, new Meta(LocalDateTime.now()), code, message);
    }

    public static ResponseDto of(Object data, String message) {
        return new ResponseDto(data, new Meta(LocalDateTime.now()),Code.OK.toString(), message);
    }
    record Meta (LocalDateTime timestamp) {}

}

