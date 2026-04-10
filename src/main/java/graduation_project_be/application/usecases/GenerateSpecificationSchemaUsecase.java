package graduation_project_be.application.usecases;

import com.fasterxml.jackson.databind.JsonNode;
import graduation_project_be.application.exceptions.BadRequestException;
import graduation_project_be.application.port.services.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerateSpecificationSchemaUsecase {

    private final GeminiService geminiService;

    public JsonNode execute(String description, JsonNode currentSchemaJson) {
        JsonNode schemaJson = geminiService.generateSpecificationSchema(description, currentSchemaJson);
        if (schemaJson == null || !schemaJson.isArray() || schemaJson.isEmpty()) {
            throw new BadRequestException("Không thể sinh schema JSON từ mô tả đã nhập");
        }
        return schemaJson;
    }
}
