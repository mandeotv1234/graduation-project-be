package graduation_project_be.application.usecases;

import graduation_project_be.domain.models.GradingTraceItem;
import java.util.ArrayList;
import java.util.List;

public final class GradingTraceCollector {
    private static final ThreadLocal<List<GradingTraceItem>> ITEMS = new ThreadLocal<>();

    private GradingTraceCollector() {}

    public static void start() {
        ITEMS.set(new ArrayList<>());
    }

    public static void add(GradingTraceItem item) {
        List<GradingTraceItem> list = ITEMS.get();
        if (list != null) {
            list.add(item);
        }
    }

    public static List<GradingTraceItem> finish() {
        List<GradingTraceItem> list = ITEMS.get();
        ITEMS.remove();
        return list != null ? list : List.of();
    }

    public static boolean isActive() {
        return ITEMS.get() != null;
    }
}
