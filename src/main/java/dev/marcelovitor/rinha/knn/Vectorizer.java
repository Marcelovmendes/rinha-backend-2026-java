package dev.marcelovitor.rinha.knn;

import java.nio.charset.StandardCharsets;

public final class Vectorizer {

    private static final int    SCALE                   = IndexHeader.SCALE;
    private static final short  ON                      = (short) SCALE;
    private static final short  SENTINEL_NO_HISTORY     = (short) -SCALE;
    private static final double MAX_AMOUNT              = 10_000;
    private static final double MAX_INSTALLMENTS        = 12;
    private static final double AMOUNT_VS_AVG_RATIO     = 10;
    private static final double MAX_MINUTES             = 1_440;
    private static final double MAX_KM                  = 1_000;
    private static final double MAX_TX_COUNT_24H        = 20;
    private static final double MAX_MERCHANT_AVG_AMOUNT = 10_000;
    private static final double MAX_HOUR_OF_DAY         = 23;
    private static final double MAX_WEEKDAY_INDEX       = 6;
    private static final double MCC_RISK_DEFAULT        = 0.5;

    private static final byte[] K_TRANSACTION      = ascii("\"transaction\"");
    private static final byte[] K_CUSTOMER         = ascii("\"customer\"");
    private static final byte[] K_MERCHANT         = ascii("\"merchant\"");
    private static final byte[] K_TERMINAL         = ascii("\"terminal\"");
    private static final byte[] K_LAST_TRANSACTION = ascii("\"last_transaction\"");
    private static final byte[] K_AMOUNT           = ascii("\"amount\"");
    private static final byte[] K_INSTALLMENTS     = ascii("\"installments\"");
    private static final byte[] K_REQUESTED_AT     = ascii("\"requested_at\"");
    private static final byte[] K_AVG_AMOUNT       = ascii("\"avg_amount\"");
    private static final byte[] K_TX_COUNT_24H     = ascii("\"tx_count_24h\"");
    private static final byte[] K_KNOWN_MERCHANTS  = ascii("\"known_merchants\"");
    private static final byte[] K_ID               = ascii("\"id\"");
    private static final byte[] K_MCC              = ascii("\"mcc\"");
    private static final byte[] K_IS_ONLINE        = ascii("\"is_online\"");
    private static final byte[] K_CARD_PRESENT     = ascii("\"card_present\"");
    private static final byte[] K_KM_FROM_HOME     = ascii("\"km_from_home\"");
    private static final byte[] K_TIMESTAMP        = ascii("\"timestamp\"");
    private static final byte[] K_KM_FROM_CURRENT  = ascii("\"km_from_current\"");

    public void vectorize(byte[] body, int offset, int length, short[] out) {
        int end = offset + length;

        int tx       = FastJson.objectStart(body, end, K_TRANSACTION,      offset);
        int customer = FastJson.objectStart(body, end, K_CUSTOMER,         offset);
        int merchant = FastJson.objectStart(body, end, K_MERCHANT,         offset);
        int terminal = FastJson.objectStart(body, end, K_TERMINAL,         offset);
        int lastKey  = FastJson.findKey   (body, end, K_LAST_TRANSACTION, offset);

        double amount        = FastJson.numberAfterKey     (body, end, K_AMOUNT,       tx);
        int    installments  = FastJson.intAfterKey        (body, end, K_INSTALLMENTS, tx);
        long   requestedAt   = FastJson.stringRangeAfterKey(body, end, K_REQUESTED_AT, tx);
        int    requestedFrom = (int) (requestedAt >>> 32);

        double customerAvg = FastJson.numberAfterKey(body, end, K_AVG_AMOUNT,   customer);
        int    txCount24h  = FastJson.intAfterKey   (body, end, K_TX_COUNT_24H, customer);

        long   merchantId  = FastJson.stringRangeAfterKey(body, end, K_ID,         merchant);
        long   mccRange    = FastJson.stringRangeAfterKey(body, end, K_MCC,        merchant);
        double merchantAvg = FastJson.numberAfterKey     (body, end, K_AVG_AMOUNT, merchant);

        boolean isOnline    = FastJson.boolAfterKey  (body, end, K_IS_ONLINE,    terminal);
        boolean cardPresent = FastJson.boolAfterKey  (body, end, K_CARD_PRESENT, terminal);
        double  kmFromHome  = FastJson.numberAfterKey(body, end, K_KM_FROM_HOME, terminal);

        boolean unknownMerchant = !FastJson.stringInArray(body, end, K_KNOWN_MERCHANTS, customer, merchantId);

        out[0] = quantize(amount / MAX_AMOUNT);
        out[1] = quantize(installments / MAX_INSTALLMENTS);
        out[2] = quantize(customerAvg <= 0 ? 1.0 : (amount / customerAvg) / AMOUNT_VS_AVG_RATIO);
        out[3] = quantize(parseHour(body, requestedFrom) / MAX_HOUR_OF_DAY);
        out[4] = quantize(dayOfWeekMonday0(body, requestedFrom) / MAX_WEEKDAY_INDEX);

        if (lastKey < 0 || FastJson.isNullAfterKey(body, end, K_LAST_TRANSACTION, offset)) {
            out[5] = SENTINEL_NO_HISTORY;
            out[6] = SENTINEL_NO_HISTORY;
        } else {
            long lastTs   = FastJson.stringRangeAfterKey(body, end, K_TIMESTAMP, lastKey);
            long reqMin   = epochMinute(body, requestedFrom);
            long lastMin  = epochMinute(body, (int) (lastTs >>> 32));
            double kmCur  = FastJson.numberAfterKey(body, end, K_KM_FROM_CURRENT, lastKey);
            out[5] = quantize(Math.max(0, reqMin - lastMin) / MAX_MINUTES);
            out[6] = quantize(kmCur / MAX_KM);
        }

        out[7]  = quantize(kmFromHome / MAX_KM);
        out[8]  = quantize(txCount24h / MAX_TX_COUNT_24H);
        out[9]  = isOnline    ? ON : 0;
        out[10] = cardPresent ? ON : 0;
        out[11] = unknownMerchant ? ON : 0;
        out[12] = quantize(mccRiskFor(body, (int) (mccRange >>> 32), (int) mccRange));
        out[13] = quantize(merchantAvg / MAX_MERCHANT_AVG_AMOUNT);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static short quantize(double value) {
        double clamped = Math.clamp(value, 0.0, 1.0);
        return (short) Math.round(clamped * SCALE);
    }

    private static int parseHour(byte[] b, int s) {
        return twoDigits(b, s + 11);
    }

    private static int dayOfWeekMonday0(byte[] b, int s) {
        int  y    = fourDigits(b, s);
        int  m    = twoDigits (b, s + 5);
        int  d    = twoDigits (b, s + 8);
        long days = daysFromCivil(y, m, d);
        return (int) Math.floorMod(days + 3, 7);
    }

    private static long epochMinute(byte[] b, int s) {
        int y  = fourDigits(b, s);
        int m  = twoDigits (b, s + 5);
        int d  = twoDigits (b, s + 8);
        int hh = twoDigits (b, s + 11);
        int mm = twoDigits (b, s + 14);
        return daysFromCivil(y, m, d) * 1440L + hh * 60L + mm;
    }

    private static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        int  yoe = (int) (y - era * 400);
        int  mp  = m + (m > 2 ? -3 : 9);
        int  doy = (153 * mp + 2) / 5 + d - 1;
        int  doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097L + doe - 719468L;
    }

    private static int twoDigits(byte[] b, int p) {
        return (b[p] - '0') * 10 + (b[p + 1] - '0');
    }

    private static int fourDigits(byte[] b, int p) {
        return (b[p] - '0') * 1000 + (b[p + 1] - '0') * 100 + (b[p + 2] - '0') * 10 + (b[p + 3] - '0');
    }

    private static double mccRiskFor(byte[] b, int s, int e) {
        if (e - s != 4) return MCC_RISK_DEFAULT;
        int code = (b[s] - '0') * 1000 + (b[s + 1] - '0') * 100 + (b[s + 2] - '0') * 10 + (b[s + 3] - '0');
        return switch (code) {
            case 5411 -> 0.15;
            case 5812 -> 0.30;
            case 5912 -> 0.20;
            case 5944 -> 0.45;
            case 7801 -> 0.80;
            case 7802 -> 0.75;
            case 7995 -> 0.85;
            case 4511 -> 0.35;
            case 5311 -> 0.25;
            case 5999 -> 0.50;
            default   -> MCC_RISK_DEFAULT;
        };
    }
}
