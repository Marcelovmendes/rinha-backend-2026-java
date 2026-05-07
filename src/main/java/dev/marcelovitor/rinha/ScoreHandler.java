package dev.marcelovitor.rinha;

import dev.marcelovitor.rinha.knn.Vectorizer;
import dev.marcelovitor.rinha.knn.VectorSearcher;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ScoreHandler implements RequestHandler {

    private static final byte[] ERROR_RESPONSE = buildHttpResponse(false, 0.0f);

    private final Vectorizer     vectorizer;
    private final VectorSearcher searcher;

    public ScoreHandler(Vectorizer vectorizer, VectorSearcher searcher) {
        this.vectorizer = vectorizer;
        this.searcher   = searcher;
    }

    @Override
    public byte[] handle(byte[] body, int offset, int length) {
        try {
            float[] vector     = vectorizer.vectorize(body, offset, length);
            float   fraudScore = searcher.computeFraudScore(vector);
            boolean approved   = searcher.isApproved(fraudScore);
            return buildHttpResponse(approved, fraudScore);
        } catch (Exception e) {
            return ERROR_RESPONSE;
        }
    }

    private static byte[] buildHttpResponse(boolean approved, float fraudScore) {
        String json    = "{\"approved\":" + approved + ",\"fraud_score\":" + String.format(Locale.US, "%.1f", fraudScore) + "}";
        byte[] body    = json.getBytes(StandardCharsets.US_ASCII);
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: keep-alive\r\n\r\n";
        byte[] headerBytes = headers.getBytes(StandardCharsets.US_ASCII);
        byte[] response    = new byte[headerBytes.length + body.length];
        System.arraycopy(headerBytes, 0, response, 0, headerBytes.length);
        System.arraycopy(body, 0, response, headerBytes.length, body.length);
        return response;
    }
}
