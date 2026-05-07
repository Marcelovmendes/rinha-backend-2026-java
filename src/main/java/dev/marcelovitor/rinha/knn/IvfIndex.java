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
    private static final int   RERANK_N      = 50;
    private static final int   M             = ProductQuantizer.M;
    private static final int   CODEBOOK_SIZE = ProductQuantizer.CODEBOOK_SIZE;
    private static final int   SUB_D         = ProductQuantizer.SUB_D;
    private static final float INF           = Float.MAX_VALUE;
    private static final float INV_32767     = 1f / 32767f;
    private static final int   BITS_PER_BYTE = 8;

    private final float[]          centroidsFlat;
    private final int[]            offsets;
    private final int[]            flatIds;
    private final byte[]           flatCodes;
    private final short[]          int16Vectors;
    private final byte[]           labels;
    private final ProductQuantizer pq;

    private IvfIndex(float[] centroidsFlat, int[] offsets, int[] flatIds,
                     byte[] flatCodes, short[] int16Vectors, byte[] labels,
                     ProductQuantizer pq) {
        this.centroidsFlat = centroidsFlat;
        this.offsets       = offsets;
        this.flatIds       = flatIds;
        this.flatCodes     = flatCodes;
        this.int16Vectors  = int16Vectors;
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
        short[]          int16Vectors  = readShorts(dataDir.resolve("vectors_int16.bin"), n * DIMENSIONS);
        byte[]           labels        = expandLabels(Files.readAllBytes(dataDir.resolve("labels.bin")), n);
        return new IvfIndex(centroidsFlat, offsets, flatIds, flatCodes, int16Vectors, labels, pq);
    }

    public float computeFraudScore(float[] query) {
        return (float) findTopKFraudCount(query) / TOP_K;
    }

    private int findTopKFraudCount(float[] query) {
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

        float[] pqDist = new float[RERANK_N];
        int[]   pqPos  = new int[RERANK_N];
        Arrays.fill(pqDist, INF);
        Arrays.fill(pqPos, -1);

        for (int p = 0; p < NPROBE; p++) {
            int cluster = probeId[p];
            int from    = offsets[cluster];
            int to      = offsets[cluster + 1];
            for (int i = from; i < to; i++) {
                float dist = pq.adcDistance(adcTable, flatCodes, i * M);
                if (dist < pqDist[RERANK_N - 1]) {
                    insertSorted(pqPos, pqDist, i, dist);
                }
            }

            if (p == NPROBE_GRAY - 1) {
                int fc = countFraudInPqTopK(pqPos);
                if (fc <= 1 || fc >= TOP_K - 1) {
                    return rerankAndCount(query, pqPos);
                }
            }
        }
        return rerankAndCount(query, pqPos);
    }

    private int countFraudInPqTopK(int[] pqPos) {
        int fc = 0;
        for (int j = 0; j < TOP_K; j++) {
            int flatPos = pqPos[j];
            if (flatPos < 0) continue;
            if (labels[flatIds[flatPos]] == 1) fc++;
        }
        return fc;
    }

    private int rerankAndCount(float[] query, int[] pqPos) {
        float[] rerankDist = new float[TOP_K];
        int[]   rerankPos  = new int[TOP_K];
        Arrays.fill(rerankDist, INF);
        Arrays.fill(rerankPos, -1);

        for (int j = 0; j < RERANK_N; j++) {
            int flatPos = pqPos[j];
            if (flatPos < 0) break;
            float dist = exactSquaredDistInt16(query, flatPos);
            if (dist < rerankDist[TOP_K - 1]) {
                insertSorted(rerankPos, rerankDist, flatPos, dist);
            }
        }

        int fc = 0;
        for (int flatPos : rerankPos) {
            if (flatPos < 0) continue;
            if (labels[flatIds[flatPos]] == 1) fc++;
        }
        return fc;
    }

    private float exactSquaredDistInt16(float[] query, int flatPos) {
        int   offset = flatPos * DIMENSIONS;
        float sum    = 0f;
        for (int d = 0; d < DIMENSIONS; d++) {
            float v    = int16Vectors[offset + d] * INV_32767;
            float diff = query[d] - v;
            sum += diff * diff;
        }
        return sum;
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

    private static short[] readShorts(Path file, int expected) throws IOException {
        short[] out = new short[expected];
        ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }
}
