package dev.marcelovitor.rinha.store;

import dev.marcelovitor.rinha.knn.KMeans;
import dev.marcelovitor.rinha.knn.ProductQuantizer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class IvfBuilder {

    private static final int  DIMENSIONS  = 14;
    private static final int  CLUSTERS    = 1024;
    private static final long SEED        = 42L;
    private static final int  FLOAT_BYTES = 4;

    public static void build(Path dataDir) throws IOException {
        Path referencesFile     = dataDir.resolve("references.bin");
        Path centroidsFile      = dataDir.resolve("centroids.bin");
        Path codebooksFile      = dataDir.resolve("codebooks.bin");
        Path clusterOffsetsFile = dataDir.resolve("cluster_offsets.bin");
        Path clusterIdsFile     = dataDir.resolve("cluster_ids.bin");
        Path clusterCodesFile   = dataDir.resolve("cluster_codes.bin");
        Path vectorsInt8File    = dataDir.resolve("vectors_int8.bin");

        long startMs = System.currentTimeMillis();

        float[][] vectors = loadVectors(referencesFile);
        int       n       = vectors.length;
        System.out.printf("IVFPQ: loaded %,d vectors%n", n);
        System.out.flush();

        long t0 = System.currentTimeMillis();
        float[][] centroids = KMeans.cluster(vectors, CLUSTERS, SEED);
        System.out.printf("IVFPQ: k-means K=%d done in %,d ms%n", CLUSTERS, System.currentTimeMillis() - t0);
        System.out.flush();

        long t1 = System.currentTimeMillis();
        int[][] idsByCluster = KMeans.assign(vectors, centroids);
        System.out.printf("IVFPQ: cluster assignment done in %,d ms%n", System.currentTimeMillis() - t1);
        System.out.flush();

        long t2 = System.currentTimeMillis();
        ProductQuantizer pq = ProductQuantizer.train(vectors, SEED);
        System.out.printf("IVFPQ: PQ training done in %,d ms%n", System.currentTimeMillis() - t2);
        System.out.flush();

        long t3 = System.currentTimeMillis();
        byte[][] allCodes = new byte[n][];
        for (int i = 0; i < n; i++) allCodes[i] = pq.encode(vectors[i]);
        System.out.printf("IVFPQ: PQ encoding done in %,d ms%n", System.currentTimeMillis() - t3);
        System.out.flush();

        int[] offsets = new int[CLUSTERS + 1];
        for (int c = 0; c < CLUSTERS; c++) offsets[c + 1] = offsets[c] + idsByCluster[c].length;

        int[]  flatIds     = new int[n];
        byte[] flatCodes   = new byte[n * ProductQuantizer.M];
        byte[] int8Vectors = new byte[n * DIMENSIONS];
        for (int c = 0; c < CLUSTERS; c++) {
            int   base = offsets[c];
            int[] ids  = idsByCluster[c];
            for (int i = 0; i < ids.length; i++) {
                int     id      = ids[i];
                int     flatPos = base + i;
                float[] v       = vectors[id];
                flatIds[flatPos] = id;
                System.arraycopy(allCodes[id], 0, flatCodes, flatPos * ProductQuantizer.M, ProductQuantizer.M);
                int int8Off = flatPos * DIMENSIONS;
                for (int d = 0; d < DIMENSIONS; d++) {
                    int8Vectors[int8Off + d] = quantizeInt8(v[d]);
                }
            }
        }
        vectors  = null;
        allCodes = null;

        writeFloats(centroidsFile, flatten(centroids));
        writeFloats(codebooksFile, flatten(pq.codebooks()));
        writeInts(clusterOffsetsFile, offsets);
        writeInts(clusterIdsFile, flatIds);
        Files.write(clusterCodesFile, flatCodes);
        Files.write(vectorsInt8File, int8Vectors);

        Files.deleteIfExists(referencesFile);

        printSummary(idsByCluster, flatIds, flatCodes, int8Vectors, n, startMs);
    }

    private static byte quantizeInt8(float v) {
        if (v < -1f) v = -1f;
        if (v >  1f) v =  1f;
        return (byte) Math.round(v * 127f);
    }

    private static float[][] loadVectors(Path file) throws IOException {
        byte[]      bytes = Files.readAllBytes(file);
        FloatBuffer fb    = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int         n     = fb.limit() / DIMENSIONS;
        float[][]   v     = new float[n][DIMENSIONS];
        for (int i = 0; i < n; i++) fb.get(v[i]);
        return v;
    }

    private static float[] flatten(float[][] m) {
        int     rows = m.length;
        int     cols = m[0].length;
        float[] out  = new float[rows * cols];
        for (int r = 0; r < rows; r++) System.arraycopy(m[r], 0, out, r * cols, cols);
        return out;
    }

    private static float[] flatten(float[][][] t) {
        int     outer = t.length;
        int     mid   = t[0].length;
        int     inner = t[0][0].length;
        float[] out   = new float[outer * mid * inner];
        int     pos   = 0;
        for (int o = 0; o < outer; o++) {
            for (int m = 0; m < mid; m++) {
                System.arraycopy(t[o][m], 0, out, pos, inner);
                pos += inner;
            }
        }
        return out;
    }

    private static void writeFloats(Path file, float[] arr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * FLOAT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(arr);
        Files.write(file, buf.array());
    }

    private static void writeInts(Path file, int[] arr) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buf.asIntBuffer().put(arr);
        Files.write(file, buf.array());
    }

    private static void printSummary(int[][] idsByCluster, int[] flatIds, byte[] flatCodes, byte[] int8Vectors, int n, long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        int  min     = Integer.MAX_VALUE;
        int  max     = 0;
        for (int[] ids : idsByCluster) {
            int s = ids.length;
            if (s < min) min = s;
            if (s > max) max = s;
        }
        System.out.printf("IVFPQ: built in %,d ms%n", elapsed);
        System.out.printf("centroids.bin       : %,d bytes%n", CLUSTERS * DIMENSIONS * FLOAT_BYTES);
        System.out.printf("codebooks.bin       : %,d bytes%n", ProductQuantizer.M * ProductQuantizer.CODEBOOK_SIZE * ProductQuantizer.SUB_D * FLOAT_BYTES);
        System.out.printf("cluster_offsets.bin : %,d bytes%n", (CLUSTERS + 1) * Integer.BYTES);
        System.out.printf("cluster_ids.bin     : %,d bytes%n", flatIds.length * Integer.BYTES);
        System.out.printf("cluster_codes.bin   : %,d bytes%n", flatCodes.length);
        System.out.printf("vectors_int8.bin    : %,d bytes%n", int8Vectors.length);
        System.out.printf("cluster sizes       : min=%,d  max=%,d  avg=%,d%n", min, max, n / CLUSTERS);
    }
}
