package io.freemytv;

import java.util.Calendar;

/** Sunrise and sunset from a latitude and longitude (NOAA's solar equations, accurate to a minute or two). */
final class Sun {
    private Sun() {}

    static double parse(String v, double fallback) {
        try {
            return Double.parseDouble(v.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * {sunrise, sunset} in epoch ms for the local day containing `now`; a 0 where the sun doesn't rise or set that day.
     * Works in UTC from the longitude, then shifts to the right day for the TV's own time zone.
     */
    static long[] times(double lat, double lon, long now) {
        Calendar local = Calendar.getInstance();
        local.setTimeInMillis(now);
        // Noon on the local date, as a UTC date number.
        Calendar noon = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        noon.clear();
        noon.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 12, 0, 0);
        long dayStart = noon.getTimeInMillis() - 12 * 3600_000L;   // 00:00 UTC of that date
        long[] out = {0, 0};
        long rise = event(lat, lon, dayStart, true), set = event(lat, lon, dayStart, false);
        // The event is for the UTC day around the longitude's local noon; move it to the local calendar day of the TV.
        long localMidnight = startOfLocalDay(local);
        out[0] = into(rise, localMidnight);
        out[1] = into(set, localMidnight);
        return out;
    }

    private static long startOfLocalDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** Shifts an instant by whole days so it falls within the 24 h that start at `midnight`. 0 stays 0. */
    private static long into(long t, long midnight) {
        if (t == 0) return 0;
        long day = 86_400_000L;
        long d = Math.floorDiv(t - midnight, day);
        return t - d * day;
    }

    /** Epoch ms of the sunrise or sunset near `dayStartUtc` (00:00 UTC of the date), or 0 if there is none. */
    private static long event(double lat, double lon, long dayStartUtc, boolean rising) {
        double jd = dayStartUtc / 86_400_000.0 + 2440587.5;
        double t = (jd + 0.5 - lon / 360.0 - 2451545.0) / 36525.0;   // Julian century, near local solar noon
        // Two passes: the second uses the first's time for the sun's position.
        double minutes = 720 - 4 * lon;
        for (int pass = 0; pass < 2; pass++) {
            double tt = t + (minutes - 720 + 4 * lon) / 1440.0 / 36525.0;
            double eq = equationOfTime(tt), decl = declination(tt);
            double cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(Math.toRadians(lat)) * Math.cos(decl)))
                    - Math.tan(Math.toRadians(lat)) * Math.tan(decl);
            if (cosH > 1 || cosH < -1) return 0;
            double h = Math.toDegrees(Math.acos(cosH));
            minutes = 720 - 4 * (lon + (rising ? h : -h)) - eq;
        }
        return dayStartUtc + Math.round(minutes * 60_000.0);
    }

    private static double meanLongitude(double t) {
        return Math.toRadians((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360);
    }

    private static double anomaly(double t) {
        return Math.toRadians(357.52911 + t * (35999.05029 - 0.0001537 * t));
    }

    private static double equationOfTime(double t) {
        double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double l0 = meanLongitude(t), m = anomaly(t);
        double y = Math.tan(Math.toRadians(obliquity(t)) / 2);
        y *= y;
        double eq = y * Math.sin(2 * l0) - 2 * e * Math.sin(m) + 4 * e * y * Math.sin(m) * Math.cos(2 * l0)
                - 0.5 * y * y * Math.sin(4 * l0) - 1.25 * e * e * Math.sin(2 * m);
        return Math.toDegrees(eq) * 4;
    }

    private static double obliquity(double t) {
        return 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
    }

    private static double declination(double t) {
        double m = anomaly(t);
        double c = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
                + Math.sin(3 * m) * 0.000289;
        double trueLong = Math.toDegrees(meanLongitude(t)) + c;
        double lambda = trueLong - 0.00569 - 0.00478 * Math.sin(Math.toRadians(125.04 - 1934.136 * t));
        double eps = obliquity(t) + 0.00256 * Math.cos(Math.toRadians(125.04 - 1934.136 * t));
        return Math.asin(Math.sin(Math.toRadians(eps)) * Math.sin(Math.toRadians(lambda)));
    }
}
