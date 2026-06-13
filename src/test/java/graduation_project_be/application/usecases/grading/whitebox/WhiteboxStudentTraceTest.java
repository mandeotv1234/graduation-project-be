package graduation_project_be.application.usecases.grading.whitebox;

import graduation_project_be.domain.models.GradingTraceItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The teacher trace keeps matched SQL evidence; the student view must show only the concise reason
 * and deduction of FAIL rules, with model-answer evidence stripped.
 */
class WhiteboxStudentTraceTest {

    private GradingTraceItem whitebox(String status, String label, double deducted) {
        return new GradingTraceItem(
                GradingTraceItem.KIND_WHITEBOX_CHECK, status, "[Whitebox] " + label, "lý do",
                null, null, "SQL_SCRIPT", "FORBIDDEN_SUBQUERY", "DEDUCTION",
                BigDecimal.valueOf(2), null, null, BigDecimal.valueOf(deducted),
                "Không có subquery", "Tìm thấy (SELECT ... tại vị trí 42", "Whitebox SELECT");
    }

    @Test
    void keepsOnlyFailWhiteboxItemsAndStripsEvidence() {
        List<GradingTraceItem> full = List.of(
                new GradingTraceItem(GradingTraceItem.KIND_TEST_CASE, GradingTraceItem.STATUS_PASS,
                        "TC1", null, null, null, null, null, null, null, null, null, null, null, null, null),
                whitebox(GradingTraceItem.STATUS_FAIL, "Cấm subquery", 2),
                whitebox(GradingTraceItem.STATUS_WARN, "Cấm SELECT *", 0),
                whitebox(GradingTraceItem.STATUS_UNVERIFIED, "Cấm correlated", 0));

        List<GradingTraceItem> concise = WhiteboxStudentTrace.concise(full);

        assertEquals(1, concise.size());
        GradingTraceItem item = concise.get(0);
        assertEquals(GradingTraceItem.STATUS_FAIL, item.status());
        assertEquals(0, BigDecimal.valueOf(2).compareTo(item.deductedPoints()));
        assertNull(item.expected(), "model-answer expected must be stripped");
        assertNull(item.actual(), "matched SQL evidence must be stripped");
        assertNull(item.ruleCondition(), "internal rule id must be hidden");
        assertTrue(item.label().contains("Cấm subquery"));
    }

    @Test
    void noWhiteboxFailures_yieldsEmpty() {
        List<GradingTraceItem> full = List.of(
                whitebox(GradingTraceItem.STATUS_WARN, "Cảnh báo", 0));
        assertTrue(WhiteboxStudentTrace.concise(full).isEmpty());
        assertTrue(WhiteboxStudentTrace.concise(null).isEmpty());
    }
}
