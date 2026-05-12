package dev.marcelovitor.rinha.store;

import dev.marcelovitor.rinha.knn.IndexHeader;
import dev.marcelovitor.rinha.knn.KMeans;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class IvfBuilder {

    private static final int  DIMS             = IndexHeader.DIMS;
    private static final int  SCALE            = IndexHeader.SCALE;
    private static final int  CLUSTERS         = 256;
    private static final int  MAX_CLUSTER_SIZE = 5000;
    private static final long SEED             = 42L;
    private static final int  BITS_PER_BYTE    = 8;

    public static void build(Path dataDir) throws IOException {
        Path referencesFile = dataDir.resolve("references.bin");
        Path labelsFile     = dataDir.resolve("labels.bin");
        Path indexFile      = dataDir.resolve("index.bin");

        long startMs = System.currentTimeMillis();

        short[][] vectors = loadAndQuantize(referencesFile);
        int       n       = vectors.length;
        byte[]    labels  = loadLabels(labelsFile, n);
        System.out.printf("IVF: loaded %,d vectors%n", n);
        System.out.flush();

        long      t0              = System.currentTimeMillis();
        short[][] coarseCentroids = KMeans.cluster(vectors, CLUSTERS, SEED);
        System.out.printf("IVF: k-means K=%d done in %,d ms%n", CLUSTERS, System.currentTimeMillis() - t0);
        System.out.flush();

        long    t1        = System.currentTimeMillis();
        int[][] coarseIds = KMeans.assign(vectors, coarseCentroids);
        System.out.printf("IVF: cluster assignment done in %,d ms%n", System.currentTimeMillis() - t1);
        System.out.flush();

        long               t2     = System.currentTimeMillis();
        List<FinalCluster> finals = splitOversize(vectors, coarseCentroids, coarseIds);
        System.out.printf("IVF: cluster splitting done in %,d ms (%d -> %d clusters)%n",
            System.currentTimeMillis() - t2, CLUSTERS, finals.size());
        System.out.flush();

        long        t3     = System.currentTimeMillis();
        IndexLayout layout = layoutClusters(vectors, labels, finals);
        System.out.printf("IVF: layout done in %,d ms%n", System.currentTimeMillis() - t3);
        System.out.flush();

        writeIndex(indexFile, layout);
        Files.deleteIfExists(referencesFile);
        Files.deleteIfExists(labelsFile);

        printSummary(layout, finals, startMs);
    }

    private static short[][] loadAndQuantize(Path file) throws IOException {
        byte[]      bytes = Files.readAllBytes(file);
        FloatBuffer fb    = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        int         n     = fb.limit() / DIMS;
        short[][]   out   = new short[n][DIMS];
        float[]     row   = new float[DIMS];
        for (int i = 0; i < n; i++) {
            fb.get(row);
            for (int d = 0; d < DIMS; d++) out[i][d] = quantize(row[d]);
        }
        return out;
    }

    private static byte[] loadLabels(Path file, int n) throws IOException {
        byte[] bitmap = Files.readAllBytes(file);
        byte[] out    = new byte[n];
        for (int i = 0; i < n; i++) {
            int byteIdx = i / BITS_PER_BYTE;
            int bitOff  = BITS_PER_BYTE - 1 - (i % BITS_PER_BYTE);
            out[i]      = (byte) ((bitmap[byteIdx] >> bitOff) & 1);
        }
        return out;
    }

    private static short quantize(float v) {
        if (v == -1f) return (short) -SCALE;
        double clamped = Math.clamp((double) v, 0.0, 1.0);
        return (short) Math.round(clamped * SCALE);
    }

    private static List<FinalCluster> splitOversize(short[][] vectors, short[][] coarseCentroids, int[][] coarseIds) {
        List<FinalCluster> finals = new ArrayList<>();
        for (int c = 0; c < coarseIds.length; c++) {
            int[] ids = coarseIds[c];
            if (ids.length <= MAX_CLUSTER_SIZE) {
                finals.add(new FinalCluster(coarseCentroids[c], ids));
                continue;
            }
            int       subK         = (ids.length + MAX_CLUSTER_SIZE - 1) / MAX_CLUSTER_SIZE;
            short[][] subVectors   = new short[ids.length][];
            for (int i = 0; i < ids.length; i++) subVectors[i] = vectors[ids[i]];
            short[][] subCentroids = KMeans.cluster(subVectors, subK, SEED + c);
            int[][]   subIds       = KMeans.assign(subVectors, subCentroids);
            for (int s = 0; s < subK; s++) {
                int[] localIds  = subIds[s];
                int[] globalIds = new int[localIds.length];
                for (int j = 0; j < localIds.length; j++) globalIds[j] = ids[localIds[j]];
                finals.add(new FinalCluster(subCentroids[s], globalIds));
            }
        }
        return finals;
    }

    private static IndexLayout layoutClusters(short[][] vectors, byte[] labels, List<FinalCluster> finals) {
        int k = finals.size();
        int n = 0;
        for (FinalCluster fc : finals) n += fc.ids().length;

        int[]   offsets         = new int[k + 1];
        short[] centroidsFlat   = new short[k * DIMS];
        short[] bboxMinFlat     = new short[k * DIMS];
        short[] bboxMaxFlat     = new short[k * DIMS];
        short[] rowsFlat        = new short[n * DIMS];
        byte[]  reorderedLabels = new byte[n];

        for (int c = 0; c < k; c++) {
            FinalCluster fc  = finals.get(c);
            int[]        ids = fc.ids();
            offsets[c + 1] = offsets[c] + ids.length;

            System.arraycopy(fc.centroid(), 0, centroidsFlat, c * DIMS, DIMS);

            short[] mins = new short[DIMS];
            short[] maxs = new short[DIMS];
            Arrays.fill(mins, Short.MAX_VALUE);
            Arrays.fill(maxs, Short.MIN_VALUE);

            int base = offsets[c];
            for (int i = 0; i < ids.length; i++) {
                short[] v      = vectors[ids[i]];
                int     rowOff = (base + i) * DIMS;
                for (int d = 0; d < DIMS; d++) {
                    short val = v[d];
                    rowsFlat[rowOff + d] = val;
                    if (val < mins[d]) mins[d] = val;
                    if (val > maxs[d]) maxs[d] = val;
                }
                reorderedLabels[base + i] = labels[ids[i]];
            }
            System.arraycopy(mins, 0, bboxMinFlat, c * DIMS, DIMS);
            System.arraycopy(maxs, 0, bboxMaxFlat, c * DIMS, DIMS);
        }
        return new IndexLayout(n, k, offsets, centroidsFlat, bboxMinFlat, bboxMaxFlat, rowsFlat, reorderedLabels);
    }

    private static void writeIndex(Path file, IndexLayout layout) throws IOException {
        long total = IndexHeader.BYTES
            + (long) (layout.k() + 1) * Integer.BYTES
            + (long) layout.centroidsFlat().length * Short.BYTES
            + (long) layout.bboxMinFlat().length * Short.BYTES
            + (long) layout.bboxMaxFlat().length * Short.BYTES
            + (long) layout.rowsFlat().length * Short.BYTES
            + layout.reorderedLabels().length;

        ByteBuffer buf = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        new IndexHeader(layout.n(), layout.k(), DIMS, SCALE).write(buf);

        writeIntArray(buf, layout.offsets());
        writeShortArray(buf, layout.centroidsFlat());
        writeShortArray(buf, layout.bboxMinFlat());
        writeShortArray(buf, layout.bboxMaxFlat());
        writeShortArray(buf, layout.rowsFlat());
        buf.put(layout.reorderedLabels());

        Files.write(file, buf.array());
    }

    private static void writeIntArray(ByteBuffer buf, int[] arr) {
        buf.asIntBuffer().put(arr);
        buf.position(buf.position() + arr.length * Integer.BYTES);
    }

    private static void writeShortArray(ByteBuffer buf, short[] arr) {
        buf.asShortBuffer().put(arr);
        buf.position(buf.position() + arr.length * Short.BYTES);
    }

    private static void printSummary(IndexLayout layout, List<FinalCluster> finals, long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        int  min     = Integer.MAX_VALUE;
        int  max     = 0;
        for (FinalCluster fc : finals) {
            int s = fc.ids().length;
            if (s < min) min = s;
            if (s > max) max = s;
        }
        long indexBytes = IndexHeader.BYTES
            + (long) (layout.k() + 1) * Integer.BYTES
            + (long) layout.centroidsFlat().length * Short.BYTES
            + (long) layout.bboxMinFlat().length * Short.BYTES
            + (long) layout.bboxMaxFlat().length * Short.BYTES
            + (long) layout.rowsFlat().length * Short.BYTES
            + layout.reorderedLabels().length;
        System.out.printf("IVF: built in %,d ms%n", elapsed);
        System.out.printf("clusters         : %,d (coarse %d, after split)%n", layout.k(), CLUSTERS);
        System.out.printf("cluster sizes    : min=%,d  max=%,d  avg=%,d%n", min, max, layout.n() / layout.k());
        System.out.printf("index.bin        : %,d bytes%n", indexBytes);
    }

    private record FinalCluster(short[] centroid, int[] ids) {
    }

    private record IndexLayout(
            int     n,
            int     k,
            int[]   offsets,
            short[] centroidsFlat,
            short[] bboxMinFlat,
            short[] bboxMaxFlat,
            short[] rowsFlat,
            byte[]  reorderedLabels) {
    }
}
