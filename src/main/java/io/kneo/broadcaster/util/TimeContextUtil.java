package io.kneo.broadcaster.util;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class TimeContextUtil {

    public static String getCurrentMomentDetailed(ZoneId zoneId) {
        ZonedDateTime zonedNow = ZonedDateTime.now(zoneId);
        LocalTime now = zonedNow.toLocalTime();
        boolean isWeekday = zonedNow.getDayOfWeek().getValue() <= 5;

        int hour = now.getHour();

        if (now.isBefore(LocalTime.of(6, 0))) {
            return "late night hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(9, 0))) {
            return isWeekday ? "early morning weekday hours, " + hour + "h"
                    : "early morning weekend hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(12, 0))) {
            return isWeekday ? "late morning weekday hours, " + hour + "h"
                    : "late morning weekend hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(13, 0))) {
            return "lunch hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(14, 0))) {
            return "early afternoon hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(17, 0))) {
            return isWeekday ? "weekday afternoon hours, " + hour + "h"
                    : "weekend afternoon hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(19, 0))) {
            return isWeekday ? "weekday early evening hours, " + hour + "h"
                    : "weekend early evening hours, " + hour + "h";
        } else if (now.isBefore(LocalTime.of(21, 0))) {
            return "evening hours, " + hour + "h";
        } else {
            return "night hours, " + hour + "h";
        }

    }


}