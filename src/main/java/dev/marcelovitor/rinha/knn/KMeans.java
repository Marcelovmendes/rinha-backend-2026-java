package dev.marcelovitor.rinha.knn;

import java.util.Random;
import java.util.stream.IntStream;

public final class KMeans {

    private static final int ITERATIONS = 10;

    private KMeans() {
    }

    public static short[][] cluster(short[][] vectors, int k, long seed) {
        return run(vectors, k, vectors[0].length, seed);
    }

    public static int[][] assign(short[][] vectors, short[][] centroids) {
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

    private static short[][] run(short[][] vectors, int k, int d, long seed) {
        int    n   = vectors.length;
        Random rng = new Random(seed);

        long      initStart = System.currentTimeMillis();
        short[][] centroids = initPlusPlus(vectors, k, d, rng);
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

    private static short[][] initPlusPlus(short[][] vectors, int k, int d, Random rng) {
        int       n         = vectors.length;
        short[][] centroids = new short[k][d];
        long[]    distances = new long[n];

        int first = rng.nextInt(n);
        System.arraycopy(vectors[first], 0, centroids[0], 0, d);

        for (int i = 0; i < n; i++) {
            distances[i] = squaredDist(vectors[i], centroids[0], d);
        }

        for (int kk = 1; kk < k; kk++) {
            double total = 0;
            for (long dist : distances) total += dist;

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
                long dist = squaredDist(vectors[i], centroids[kk], d);
                if (dist < distances[i]) distances[i] = dist;
            }
        }
        return centroids;
    }

    private static void assignParallel(short[][] vectors, short[][] centroids, int[] assignments, int n, int d) {
        IntStream.range(0, n).parallel().forEach(i -> assignments[i] = nearestIdx(vectors[i], centroids, d));
    }

    private static void updateCentroids(short[][] vectors, short[][] centroids, int[] assignments, int n, int k, int d) {
        long[][] acc    = new long[k][d];
        int[]    counts = new int[k];

        for (int i = 0; i < n; i++) {
            int     c = assignments[i];
            counts[c]++;
            short[] v = vectors[i];
            long[]  a = acc[c];
            for (int dd = 0; dd < d; dd++) a[dd] += v[dd];
        }

        for (int c = 0; c < k; c++) {
            int cnt = counts[c];
            if (cnt > 0) {
                long[]  a   = acc[c];
                short[] cen = centroids[c];
                for (int dd = 0; dd < d; dd++) cen[dd] = (short) Math.round((double) a[dd] / cnt);
            }
        }
    }

    private static int nearestIdx(short[] vec, short[][] centroids, int d) {
        int  best     = 0;
        long bestDist = Long.MAX_VALUE;
        for (int c = 0; c < centroids.length; c++) {
            long dist = squaredDist(vec, centroids[c], d);
            if (dist < bestDist) {
                bestDist = dist;
                best     = c;
            }
        }
        return best;
    }

    private static long squaredDist(short[] a, short[] b, int d) {
        long sum = 0;
        for (int i = 0; i < d; i++) {
            int diff = a[i] - b[i];
            sum += (long) diff * diff;
        }
        return sum;
    }
}
