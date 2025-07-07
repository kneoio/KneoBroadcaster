package io.kneo.broadcaster.util;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeContextUtil {

    public static String getCurrentMomentDetailed() {
        return getCurrentMomentDetailed(ZoneId.systemDefault());
    }

    public static String getCurrentMomentDetailed(ZoneId zoneId) {
        ZonedDateTime zonedNow = ZonedDateTime.now(zoneId);
        LocalTime now = zonedNow.toLocalTime();
        boolean isWeekday = zonedNow.getDayOfWeek().getValue() <= 5;

        if (now.isBefore(LocalTime.of(6, 0))) {
            return "late night";
        } else if (now.isBefore(LocalTime.of(9, 0))) {
            return isWeekday ? "early morning on a weekday" : "early morning on weekend";
        } else if (now.isBefore(LocalTime.of(12, 0))) {
            return isWeekday ? "late morning on a weekday" : "late morning on weekend";
        } else if (now.isBefore(LocalTime.of(13, 0))) {
            return "lunch time";
        } else if (now.isBefore(LocalTime.of(14, 0))) {
            return "early afternoon";
        } else if (now.isBefore(LocalTime.of(17, 0))) {
            return isWeekday ? "afternoon on a weekday" : "afternoon on weekend";
        } else if (now.isBefore(LocalTime.of(19, 0))) {
            return isWeekday ? "early evening on a weekday" : "early evening on weekend";
        } else if (now.isBefore(LocalTime.of(21, 0))) {
            return "evening";
        } else {
            return "night";
        }
    }


}