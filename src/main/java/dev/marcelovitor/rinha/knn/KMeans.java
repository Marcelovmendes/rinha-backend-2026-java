package dev.marcelovitor.rinha.knn;

import java.util.Random;
import java.util.stream.IntStream;

public final class KMeans {

    private static final int ITERATIONS = 10;

    private KMeans() {
    }

    public static float[][] cluster(float[][] vectors, int k, long seed) {
        return run(vectors, k, vectors[0].length, seed);
    }

    public static float[][] clusterSub(float[][] subVectors, int k, long seed) {
        return run(subVectors, k, subVectors[0].length, seed);
    }

    public static int[][] assign(float[][] vectors, float[][] centroids) {
        int n = vectors.length;
        int k = centroids.length;
        int d = centroids[0].length;

        int[] all   = new int[n];
        int[] sizes = new int[k];

        IntStream.range(0, n).parallel().forEach(i -> all[i] = nearestIdx(vectors[i], centroids, d));
        for (int i = 0; i < n; i++) sizes[all[i]]++;

        int[][] inverted = new int[k][];
        for (int c = 0; c < k; c++) inverted[c] = new int[sizes[c]];

        int[] pos = new int[k];
        for (int i = 0; i < n; i++) {
            int c = all[i];
            inverted[c][pos[c]++] = i;
        }
        return inverted;
    }

    public static int nearestSub(float[] sub, float[][] centroids, int d) {
        return nearestIdx(sub, centroids, d);
    }

    private static float[][] run(float[][] vectors, int k, int d, long seed) {
        int    n   = vectors.length;
        Random rng = new Random(seed);

        long initStart = System.currentTimeMillis();
        float[][] centroids = initPlusPlus(vectors, k, d, rng);
        System.out.printf("    k-means init done in %,d ms%n", System.currentTimeMillis() - initStart);
        System.out.flush();

        int[] assignments = new int[n];
        for (int iter = 0; iter < ITERATIONS; iter++) {
            long iterStart = System.currentTimeMillis();
            assignParallel(vectors, centroids, assignments, n, d);
            updateCentroids(vectors, centroids, assignments, n, k, d);
            System.out.printf("    k-means iter %d/%d: %,d ms%n", iter + 1, ITERATIONS, System.currentTimeMillis() - iterStart);
            System.out.flush();
        }
        return centroids;
    }

    private static float[][] initPlusPlus(float[][] vectors, int k, int d, Random rng) {
        int       n         = vectors.length;
        float[][] centroids = new float[k][d];
        float[]   distances = new float[n];

        int first = rng.nextInt(n);
        System.arraycopy(vectors[first], 0, centroids[0], 0, d);

        for (int i = 0; i < n; i++) {
            distances[i] = squaredDist(vectors[i], centroids[0], d);
        }

        for (int kk = 1; kk < k; kk++) {
            double total = 0;
            for (float dist : distances) total += dist;

            double threshold  = rng.nextDouble() * total;
            double cumulative = 0;
            int    chosen     = n - 1;
            for (int i = 0; i < n; i++) {
                cumulative += distances[i];
                if (cumulative >= threshold) {
                    chosen = i;
                    break;
                }
            }
            System.arraycopy(vectors[chosen], 0, centroids[kk], 0, d);

            for (int i = 0; i < n; i++) {
                float dist = squaredDist(vectors[i], centroids[kk], d);
                if (dist < distances[i]) distances[i] = dist;
            }
        }
        return centroids;
    }

    private static void assignParallel(float[][] vectors, float[][] centroids, int[] assignments, int n, int d) {
        IntStream.range(0, n).parallel().forEach(i -> assignments[i] = nearestIdx(vectors[i], centroids, d));
    }

    private static void updateCentroids(float[][] vectors, float[][] centroids, int[] assignments, int n, int k, int d) {
        float[][] acc    = new float[k][d];
        int[]     counts = new int[k];

        for (int i = 0; i < n; i++) {
            int     c = assignments[i];
            counts[c]++;
            float[] v = vectors[i];
            float[] a = acc[c];
            for (int dd = 0; dd < d; dd++) a[dd] += v[dd];
        }

        for (int c = 0; c < k; c++) {
            if (counts[c] > 0) {
                float   inv = 1f / counts[c];
                float[] a   = acc[c];
                float[] cen = centroids[c];
                for (int dd = 0; dd < d; dd++) cen[dd] = a[dd] * inv;
            }
        }
    }

    private static int nearestIdx(float[] vec, float[][] centroids, int d) {
        int   best     = 0;
        float bestDist = Float.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            float dist = squaredDist(vec, centroids[c], d);
            if (dist < bestDist) {
                bestDist = dist;
                best     = c;
            }
        }
        return best;
    }

    private static float squaredDist(float[] a, float[] b, int d) {
        float sum = 0f;
        for (int i = 0; i < d; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }
        return sum;
    }
}
