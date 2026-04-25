package graduation_project_be.shared.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class TimeUtils {
    public static final ZoneId VIETNAM_ZONE_ID = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Get the current local date time fixed to Vietnam TimeZone regardless of server configuration.
     * Use this instead of LocalDateTime.now() to ensure timezone consistency.
     * @return Current date time in Vietnam Zone
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(VIETNAM_ZONE_ID);
    }
}
