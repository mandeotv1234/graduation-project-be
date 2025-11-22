package graduation_project_be.adapter.web.api.dtos.response;

import java.util.Date;

public record ResponseDto (
        Object data,
        Meta meta
){
    public ResponseDto (Object data){
        this(data, new Meta(new Date()));
    }
    record Meta (Date timestamp) {}
}
