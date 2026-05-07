package dev.marcelovitor.rinha.knn;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marcelovitor.rinha.json.IsoTimestamp;
import dev.marcelovitor.rinha.model.LastTransactionData;
import dev.marcelovitor.rinha.model.TransactionPayload;

import java.io.IOException;
import java.util.List;

public final class Vectorizer {

    private static final float  SENTINEL_NO_HISTORY     = -1f;
    private static final double MAX_AMOUNT              = 10_000;
    private static final double MAX_INSTALLMENTS        = 12;
    private static final double AMOUNT_VS_AVG_RATIO     = 10;
    private static final double MAX_MINUTES             = 1_440;
    private static final double MAX_KM                  = 1_000;
    private static final double MAX_TX_COUNT_24H        = 20;
    private static final double MAX_MERCHANT_AVG_AMOUNT = 10_000;
    private static final double MAX_HOUR_OF_DAY         = 23;
    private static final double MAX_WEEKDAY_INDEX       = 6;
    private static final float  MCC_RISK_DEFAULT        = 0.5f;

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static {
        try {
            MAPPER.readValue("{}", TransactionPayload.class);
        } catch (Exception ignored) {}
    }

    public float[] vectorize(byte[] body, int offset, int length) throws IOException {
        TransactionPayload p = MAPPER.readValue(body, offset, length, TransactionPayload.class);

        String requestedAt = p.transaction().requestedAt();
        int    hour        = IsoTimestamp.hour(requestedAt);
        int    dayOfWeek   = IsoTimestamp.dayOfWeekMondayBased(requestedAt);

        float minutesSinceLast;
        float kmFromLast;
        LastTransactionData last = p.lastTransaction();
        if (last == null) {
            minutesSinceLast = SENTINEL_NO_HISTORY;
            kmFromLast       = SENTINEL_NO_HISTORY;
        } else {
            long lastEpoch = IsoTimestamp.epochSeconds(last.timestamp());
            long reqEpoch  = IsoTimestamp.epochSeconds(requestedAt);
            long minutes   = (reqEpoch - lastEpoch) / 60;
            minutesSinceLast = clamp(minutes / MAX_MINUTES);
            kmFromLast       = clamp(last.kmFromCurrent() / MAX_KM);
        }

        List<String> knownMerchants = p.customer().knownMerchants();
        String       merchantId     = p.merchant().id();
        boolean unknownMerchant = isUnknownMerchant(knownMerchants, merchantId);

        return new float[] {
            clamp(p.transaction().amount() / MAX_AMOUNT),
            clamp(p.transaction().installments() / MAX_INSTALLMENTS),
            clamp((p.transaction().amount() / p.customer().avgAmount()) / AMOUNT_VS_AVG_RATIO),
            (float) (hour / MAX_HOUR_OF_DAY),
            (float) (dayOfWeek / MAX_WEEKDAY_INDEX),
            minutesSinceLast,
            kmFromLast,
            clamp(p.terminal().kmFromHome() / MAX_KM),
            clamp(p.customer().txCount24h() / MAX_TX_COUNT_24H),
            p.terminal().isOnline()    ? 1f : 0f,
            p.terminal().cardPresent() ? 1f : 0f,
            unknownMerchant ? 1f : 0f,
            mccRiskFor(p.merchant().mcc()),
            clamp(p.merchant().avgAmount() / MAX_MERCHANT_AVG_AMOUNT)
        };
    }

    private static boolean isUnknownMerchant(List<String> knownMerchants, String merchantId) {
        if (knownMerchants == null || merchantId == null) return true;
        for (String known : knownMerchants) {
            if (merchantId.equals(known)) return false;
        }
        return true;
    }

    private static float mccRiskFor(String mcc) {
        if (mcc == null) return MCC_RISK_DEFAULT;
        return switch (mcc) {
            case "5411" -> 0.15f;
            case "5812" -> 0.30f;
            case "5912" -> 0.20f;
            case "5944" -> 0.45f;
            case "7801" -> 0.80f;
            case "7802" -> 0.75f;
            case "7995" -> 0.85f;
            case "4511" -> 0.35f;
            case "5311" -> 0.25f;
            case "5999" -> 0.50f;
            default     -> MCC_RISK_DEFAULT;
        };
    }

    private static float clamp(double value) {
        return (float) Math.clamp(value, 0.0, 1.0);
    }
}
