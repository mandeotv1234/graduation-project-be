package graduation_project_be.application.port.services;

import java.util.Map;

/** Port for rendering Thymeleaf templates to PDF bytes. */
public interface PdfRenderService {
    /**
     * Render the named Thymeleaf template with the given model variables.
     *
     * @param templateName classpath template name (without .html extension)
     * @param model        variables passed to the template engine
     * @return PDF bytes (starts with %PDF)
     */
    byte[] render(String templateName, Map<String, Object> model);
}
