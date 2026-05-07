package dev.marcelovitor.rinha.knn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class IvfIndex {

    private static final int   DIMENSIONS    = 14;
    private static final int   K             = 1024;
    private static final int   NPROBE        = 24;
    private static final int   NPROBE_GRAY   = 8;
    private static final int   TOP_K         = 5;
    private static final int   M             = ProductQuantizer.M;
    private static final int   CODEBOOK_SIZE = ProductQuantizer.CODEBOOK_SIZE;
    private static final int   SUB_D         = ProductQuantizer.SUB_D;
    private static final float INF           = Float.MAX_VALUE;
    private static final int   BITS_PER_BYTE = 8;

    private final float[]          centroidsFlat;
    private final int[]            offsets;
    private final int[]            flatIds;
    private final byte[]           flatCodes;
    private final byte[]           labels;
    private final ProductQuantizer pq;

    private IvfIndex(float[] centroidsFlat, int[] offsets, int[] flatIds, byte[] flatCodes, byte[] labels, ProductQuantizer pq) {
        this.centroidsFlat = centroidsFlat;
        this.offsets       = offsets;
        this.flatIds       = flatIds;
        this.flatCodes     = flatCodes;
        this.labels        = labels;
        this.pq            = pq;
    }

    public static IvfIndex load(Path dataDir) throws IOException {
        float[]          centroidsFlat = readFloats(dataDir.resolve("centroids.bin"), K * DIMENSIONS);
        ProductQuantizer pq            = loadPq(dataDir.resolve("codebooks.bin"));
        int[]            offsets       = readInts(dataDir.resolve("cluster_offsets.bin"), K + 1);
        int              n             = offsets[K];
        int[]            flatIds       = readInts(dataDir.resolve("cluster_ids.bin"), n);
        byte[]           flatCodes     = Files.readAllBytes(dataDir.resolve("cluster_codes.bin"));
        byte[]           labels        = expandLabels(Files.readAllBytes(dataDir.resolve("labels.bin")), n);
        return new IvfIndex(centroidsFlat, offsets, flatIds, flatCodes, labels, pq);
    }

    public float computeFraudScore(float[] query) {
        return (float) countFraudInTopK(query) / TOP_K;
    }

    private int countFraudInTopK(float[] query) {
        float[] probeDist = new float[NPROBE];
        int[]   probeId   = new int[NPROBE];
        Arrays.fill(probeDist, INF);

        for (int c = 0; c < K; c++) {
            float dist = squaredDist(query, centroidsFlat, c * DIMENSIONS);
            if (dist < probeDist[NPROBE - 1]) {
                insertSorted(probeId, probeDist, c, dist);
            }
        }

        float[][] adcTable = new float[M][CODEBOOK_SIZE];
        pq.buildAdcTable(query, adcTable);

        float[] topDist = new float[TOP_K];
        int[]   topId   = new int[TOP_K];
        Arrays.fill(topDist, INF);
        Arrays.fill(topId, -1);

        for (int p = 0; p < NPROBE; p++) {
            int cluster = probeId[p];
            int from    = offsets[cluster];
            int to      = offsets[cluster + 1];
            for (int i = from; i < to; i++) {
                float dist = pq.adcDistance(adcTable, flatCodes, i * M);
                if (dist < topDist[TOP_K - 1]) {
                    insertSorted(topId, topDist, flatIds[i], dist);
                }
            }

            if (p == NPROBE_GRAY - 1) {
                int fc = countFraud(topId);
                if (fc <= 1 || fc >= TOP_K - 1) return fc;
            }
        }
        return countFraud(topId);
    }

    private int countFraud(int[] topId) {
        int fc = 0;
        for (int id : topId) {
            if (id >= 0 && labels[id] == 1) fc++;
        }
        return fc;
    }

    private static void insertSorted(int[] ids, float[] dists, int id, float dist) {
        int last = dists.length - 1;
        int pos  = last;
        while (pos > 0 && dists[pos - 1] > dist) {
            dists[pos] = dists[pos - 1];
            ids[pos]   = ids[pos - 1];
            pos--;
        }
        dists[pos] = dist;
        ids[pos]   = id;
    }

    private static float squaredDist(float[] query, float[] flat, int offset) {
        float sum = 0f;
        for (int d = 0; d < DIMENSIONS; d++) {
            float diff = query[d] - flat[offset + d];
            sum += diff * diff;
        }
        return sum;
    }

    private static ProductQuantizer loadPq(Path file) throws IOException {
        float[]     flat      = readFloats(file, M * CODEBOOK_SIZE * SUB_D);
        float[][][] codebooks = new float[M][CODEBOOK_SIZE][SUB_D];
        int         p         = 0;
        for (int m = 0; m < M; m++) {
            for (int c = 0; c < CODEBOOK_SIZE; c++) {
                for (int d = 0; d < SUB_D; d++) {
                    codebooks[m][c][d] = flat[p++];
                }
            }
        }
        return ProductQuantizer.load(codebooks);
    }

    private static byte[] expandLabels(byte[] bitmap, int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            int byteIdx = i / BITS_PER_BYTE;
            int bitOff  = BITS_PER_BYTE - 1 - (i % BITS_PER_BYTE);
            out[i]      = (byte) ((bitmap[byteIdx] >> bitOff) & 1);
        }
        return out;
    }

    private static float[] readFloats(Path file, int expected) throws IOException {
        float[] out = new float[expected];
        ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    private static int[] readInts(Path file, int expected) throws IOException {
        int[] out = new int[expected];
        ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
        return out;
    }
}
