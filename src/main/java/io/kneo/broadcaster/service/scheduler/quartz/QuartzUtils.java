package io.kneo.broadcaster.service.scheduler.quartz;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public final class QuartzUtils {

    private QuartzUtils() {}

    public static String convertTimeToCron(String time) {
        LocalTime localTime = LocalTime.parse(time);
        return String.format("0 %d %d * * ?", localTime.getMinute(), localTime.getHour());
    }

    public static String convertTimeToCron(String time, List<String> weekdays) {
        LocalTime localTime = LocalTime.parse(time);
        String dayOfWeek = convertWeekdaysToCron(weekdays);
        return String.format("0 %d %d ? * %s", localTime.getMinute(), localTime.getHour(), dayOfWeek);
    }

    public static String buildCronForInstant(int hour, int minute, List<String> dows) {
        String days = String.join(",", dows);
        return String.format("0 %d %d ? * %s", minute, hour, days);
    }

    public static String convertWeekdaysToCron(List<String> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            return "*";
        }

        return weekdays.stream()
                .map(QuartzUtils::convertWeekdayToCronFormat)
                .reduce((first, second) -> first + "," + second)
                .orElse("*");
    }

    public static String convertWeekdayToCronFormat(String weekday) {
        return switch (weekday.toUpperCase()) {
            case "MONDAY" -> "MON";
            case "TUESDAY" -> "TUE";
            case "WEDNESDAY" -> "WED";
            case "THURSDAY" -> "THU";
            case "FRIDAY" -> "FRI";
            case "SATURDAY" -> "SAT";
            case "SUNDAY" -> "SUN";
            default -> weekday.substring(0, 3).toUpperCase();
        };
    }

    public static List<String> convertWeekdaysToAbbrev(List<String> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            return List.of("MON","TUE","WED","THU","FRI","SAT","SUN");
        }

        List<String> out = new ArrayList<>();
        for (String d : weekdays) {
            if (d == null) continue;
            switch (d.toUpperCase()) {
                case "MON", "MONDAY" -> out.add("MON");
                case "TUE", "TUESDAY" -> out.add("TUE");
                case "WED", "WEDNESDAY" -> out.add("WED");
                case "THU", "THURSDAY" -> out.add("THU");
                case "FRI", "FRIDAY" -> out.add("FRI");
                case "SAT", "SATURDAY" -> out.add("SAT");
                case "SUN", "SUNDAY" -> out.add("SUN");
                default -> {}
            }
        }

        if (out.isEmpty()) {
            return List.of("MON","TUE","WED","THU","FRI","SAT","SUN");
        }

        return out;
    }
}
