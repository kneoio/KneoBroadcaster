package io.kneo.broadcaster.service.scheduler;

import java.time.LocalTime;

public class TimeUtils {

    public static boolean isAtTime(String current, String target) {
        return current.equals(target);
    }

    public static boolean isWarningTime(String current, String end, int warningMinutes) {
        LocalTime c = LocalTime.parse(current);
        LocalTime e = LocalTime.parse(end);
        LocalTime warning = e.minusMinutes(warningMinutes);
        return !c.isBefore(warning) && c.isBefore(e);
    }
}