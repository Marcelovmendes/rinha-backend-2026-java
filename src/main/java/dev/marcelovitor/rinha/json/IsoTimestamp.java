package dev.marcelovitor.rinha.json;

public final class IsoTimestamp {

    private IsoTimestamp() {}

    private static final int[] SAKAMOTO_T = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};

    public static int hour(String iso) {
        return digit2(iso, 11);
    }

    public static int dayOfWeekMondayBased(String iso) {
        int y = digit4(iso, 0);
        int m = digit2(iso, 5);
        int d = digit2(iso, 8);
        int sunBased = sakamoto(y, m, d);
        return Math.floorMod(sunBased - 1, 7);
    }

    public static long epochSeconds(String iso) {
        int y  = digit4(iso, 0);
        int mo = digit2(iso, 5);
        int d  = digit2(iso, 8);
        int h  = digit2(iso, 11);
        int mi = digit2(iso, 14);
        int s  = digit2(iso, 17);
        return toEpochSeconds(y, mo, d, h, mi, s);
    }

    private static int digit2(String s, int o) {
        return (s.charAt(o) - '0') * 10 + (s.charAt(o + 1) - '0');
    }

    private static int digit4(String s, int o) {
        return digit2(s, o) * 100 + digit2(s, o + 2);
    }

    private static int sakamoto(int y, int m, int d) {
        if (m < 3) y -= 1;
        return Math.floorMod(y + y / 4 - y / 100 + y / 400 + SAKAMOTO_T[m - 1] + d, 7);
    }

    private static long toEpochSeconds(int y, int mo, int d, int h, int mi, int s) {
        int yy  = y - (mo <= 2 ? 1 : 0);
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int doy = (153 * (mo > 2 ? mo - 3 : mo + 9) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        long days = (long) era * 146097 + doe - 719468;
        return days * 86400L + h * 3600L + mi * 60L + s;
    }
}
