package dev.marcelovitor.rinha.knn;

public interface FraudDetector {
    int size();
    int topKFraudCount(short[] q);
}
