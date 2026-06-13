package graduation_project_be.application.usecases;

import graduation_project_be.application.usecases.grading.whitebox.WhiteboxCatalog;
import graduation_project_be.application.usecases.grading.whitebox.WhiteboxCatalogEntry;
import lombok.RequiredArgsConstructor;

import java.util.List;

/** Returns the backend-owned white-box rule catalog for a question type (SELECT_QUERY in v1). */
@RequiredArgsConstructor
public class WhiteboxCatalogUsecase {

    private static final String DEFAULT_QUESTION_TYPE = "SELECT_QUERY";

    private final WhiteboxCatalog catalog;

    public List<WhiteboxCatalogEntry> execute(String questionType) {
        String type = (questionType == null || questionType.isBlank())
                ? DEFAULT_QUESTION_TYPE
                : questionType.trim().toUpperCase(java.util.Locale.ROOT);
        return catalog.entriesFor(type);
    }
}
