package dev.marcelovitor.rinha.knn;

public final class VectorSearcher {

    private static final float FRAUD_THRESHOLD = 0.6f;

    private final IvfIndex index;

    public VectorSearcher(IvfIndex index) {
        this.index = index;
    }

    public float computeFraudScore(float[] query) {
        return index.computeFraudScore(query);
    }

    public boolean isApproved(float fraudScore) {
        return fraudScore < FRAUD_THRESHOLD;
    }
}
