package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import graduation_project_be.application.port.services.ObjectMapperService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JacksonObjectMapperService<T> implements ObjectMapperService<T> {

    private final ObjectMapper objectMapper;

    @Override
    public T convert(Object value, TypeReference<T> typeReference) {
        try {
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            objectMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
            return objectMapper.convertValue(value, typeReference);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize", e);
        }
    }

    @Override
    public String toJsonString(Object object) {
        try {
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            objectMapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize", e);
        }
    }
}
