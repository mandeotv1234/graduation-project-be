package graduation_project_be.shared.infrastructure.service_impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import graduation_project_be.shared.application.port.services.ObjectMapperService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonObjectMapperService<T> implements ObjectMapperService<T> {

    private final ObjectMapper objectMapper;

    @Override
    public T convert(Object value, TypeReference<T> typeReference) {
        try {
            objectMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
            return objectMapper.convertValue(value, typeReference);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize", e);
        }
    }

    @Override
    public String toJsonString(Object object) {
        try {
            objectMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }
}
