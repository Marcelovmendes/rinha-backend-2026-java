package dev.marcelovitor.rinha;

import dev.marcelovitor.rinha.knn.IvfIndex;
import dev.marcelovitor.rinha.knn.Vectorizer;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Semaphore;

public final class ScoreHandler implements RequestHandler {

    private static final int      QUERY_DIMS      = 14;
    private static final int      FRAUD_THRESHOLD = 3;
    private static final byte[][] RESPONSES       = buildResponses();
    private static final byte[]   ERROR_RESPONSE  = buildHttpResponse(false, "0.0");

    private final Vectorizer                vectorizer;
    private final IvfIndex                  index;
    private final Semaphore                 knnSlots    = new Semaphore(Integer.getInteger("knn.slots", 1));
    private final ThreadLocal<short[]>      queryBuffer = ThreadLocal.withInitial(() -> new short[QUERY_DIMS]);

    public ScoreHandler(Vectorizer vectorizer, IvfIndex index) {
        this.vectorizer = vectorizer;
        this.index      = index;
    }

    @Override
    public byte[] handle(byte[] body, int offset, int length) {
        knnSlots.acquireUninterruptibly();
        try {
            short[] q = queryBuffer.get();
            vectorizer.vectorize(body, offset, length, q);
            int frauds = index.countFraudsInTop5(q);
            return RESPONSES[frauds];
        } catch (Exception e) {
            return ERROR_RESPONSE;
        } finally {
            knnSlots.release();
        }
    }

    private static byte[][] buildResponses() {
        byte[][] out = new byte[IvfIndex.TOP_K + 1][];
        for (int frauds = 0; frauds <= IvfIndex.TOP_K; frauds++) {
            boolean approved = frauds < FRAUD_THRESHOLD;
            String  score    = String.format(Locale.US, "%.1f", (float) frauds / IvfIndex.TOP_K);
            out[frauds] = buildHttpResponse(approved, score);
        }
        return out;
    }

    private static byte[] buildHttpResponse(boolean approved, String fraudScore) {
        String json    = "{\"approved\":" + approved + ",\"fraud_score\":" + fraudScore + "}";
        byte[] body    = json.getBytes(StandardCharsets.US_ASCII);
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n";
        byte[] headerBytes = headers.getBytes(StandardCharsets.US_ASCII);
        byte[] response    = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, response, 0, headerBytes.length);
        System.arraycopy(body, 0, response, headerBytes.length, body.length);
        return response;
    }
}
