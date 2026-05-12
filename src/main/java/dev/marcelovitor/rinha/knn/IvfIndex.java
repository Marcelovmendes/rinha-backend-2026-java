package dev.marcelovitor.rinha.knn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class IvfIndex {

    public static final  int   TOP_K           = 5;
    private static final int   DIMS            = IndexHeader.DIMS;

    private final int     k;
    private final int[]   offsets;
    private final short[] centroidsFlat;
    private final short[] bboxMinFlat;
    private final short[] bboxMaxFlat;
    private final short[] rowsFlat;
    private final byte[]  labels;
    private final int[]   nonEmptyClusters;

    private IvfIndex(int k, int[] offsets, short[] centroidsFlat, short[] bboxMinFlat,
                     short[] bboxMaxFlat, short[] rowsFlat, byte[] labels, int[] nonEmptyClusters) {
        this.k                = k;
        this.offsets          = offsets;
        this.centroidsFlat    = centroidsFlat;
        this.bboxMinFlat      = bboxMinFlat;
        this.bboxMaxFlat      = bboxMaxFlat;
        this.rowsFlat         = rowsFlat;
        this.labels           = labels;
        this.nonEmptyClusters = nonEmptyClusters;
    }

    public static IvfIndex load(Path indexBin) throws IOException {
        try (FileChannel ch = FileChannel.open(indexBin, StandardOpenOption.READ)) {
            ByteBuffer chunk = ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);

            ByteBuffer  headBuf = readBuffer(ch, IndexHeader.BYTES);
            IndexHeader h       = IndexHeader.read(headBuf);

            int n = h.n();
            int k = h.k();

            int[]   offsets       = readInts(ch, chunk, k + 1);
            short[] centroidsFlat = readShorts(ch, chunk, k * DIMS);
            short[] bboxMinFlat   = readShorts(ch, chunk, k * DIMS);
            short[] bboxMaxFlat   = readShorts(ch, chunk, k * DIMS);
            short[] rowsFlat      = readShorts(ch, chunk, n * DIMS);
            byte[]  labels        = readBytes(ch, chunk, n);

            int[] nonEmptyClusters = collectNonEmpty(offsets, k);
            return new IvfIndex(k, offsets, centroidsFlat, bboxMinFlat, bboxMaxFlat, rowsFlat, labels, nonEmptyClusters);
        }
    }

    public int countFraudsInTop5(short[] q) {
        Top5 top    = new Top5();
        int  chosen = bestCluster(q);
        scanCluster(chosen, q, top);

        long worst = top.worst();
        for (int idx = 0; idx < nonEmptyClusters.length; idx++) {
            int c = nonEmptyClusters[idx];
            if (c == chosen) continue;
            if (bboxLowerBound(c, q, worst) <= worst) {
                scanCluster(c, q, top);
                worst = top.worst();
            }
        }
        return top.countFrauds(labels);
    }

    private int bestCluster(short[] q) {
        long bestDist = Long.MAX_VALUE;
        int  best     = nonEmptyClusters[0];
        for (int idx = 0; idx < nonEmptyClusters.length; idx++) {
            int  c    = nonEmptyClusters[idx];
            long dist = squaredDist(q, centroidsFlat, c * DIMS);
            if (dist < bestDist) {
                bestDist = dist;
                best     = c;
            }
        }
        return best;
    }

    private long bboxLowerBound(int c, short[] q, long limit) {
        int  base = c * DIMS;
        long sum  = 0;
        for (int d = 0; d < DIMS; d++) {
            int qd = q[d];
            int lo = bboxMinFlat[base + d];
            int hi = bboxMaxFlat[base + d];
            int delta;
            if (qd < lo)      delta = lo - qd;
            else if (qd > hi) delta = qd - hi;
            else              continue;
            sum += (long) delta * delta;
            if (sum > limit) return sum;
        }
        return sum;
    }

    private void scanCluster(int c, short[] q, Top5 top) {
        int from = offsets[c];
        int to   = offsets[c + 1];
        if (from >= to) return;

        final short[] r   = rowsFlat;
        final int     q0  = q[0],  q1  = q[1],  q2  = q[2],  q3  = q[3],  q4  = q[4],  q5  = q[5],  q6  = q[6];
        final int     q7  = q[7],  q8  = q[8],  q9  = q[9],  q10 = q[10], q11 = q[11], q12 = q[12], q13 = q[13];

        long worst = top.worst();

        for (int i = from; i < to; i++) {
            int  base = i * DIMS;
            long sum  = sq(r[base]      - q0);   if (sum > worst) continue;
            sum      += sq(r[base + 1]  - q1);   if (sum > worst) continue;
            sum      += sq(r[base + 2]  - q2);   if (sum > worst) continue;
            sum      += sq(r[base + 3]  - q3);   if (sum > worst) continue;
            sum      += sq(r[base + 4]  - q4);   if (sum > worst) continue;
            sum      += sq(r[base + 5]  - q5);   if (sum > worst) continue;
            sum      += sq(r[base + 6]  - q6);   if (sum > worst) continue;
            sum      += sq(r[base + 7]  - q7);   if (sum > worst) continue;
            sum      += sq(r[base + 8]  - q8);   if (sum > worst) continue;
            sum      += sq(r[base + 9]  - q9);   if (sum > worst) continue;
            sum      += sq(r[base + 10] - q10);  if (sum > worst) continue;
            sum      += sq(r[base + 11] - q11);  if (sum > worst) continue;
            sum      += sq(r[base + 12] - q12);  if (sum > worst) continue;
            sum      += sq(r[base + 13] - q13);  if (sum > worst) continue;
            top.add(sum, i);
            worst = top.worst();
        }
    }

    private static long sq(int x) {
        return (long) x * x;
    }

    private static long squaredDist(short[] q, short[] flat, int offset) {
        long sum = 0;
        for (int d = 0; d < DIMS; d++) {
            int diff = q[d] - flat[offset + d];
            sum += (long) diff * diff;
        }
        return sum;
    }

    private static int[] collectNonEmpty(int[] offsets, int k) {
        int count = 0;
        for (int c = 0; c < k; c++) if (offsets[c + 1] > offsets[c]) count++;
        int[] out = new int[count];
        int   pos = 0;
        for (int c = 0; c < k; c++) if (offsets[c + 1] > offsets[c]) out[pos++] = c;
        return out;
    }

    private static ByteBuffer readBuffer(FileChannel ch, int bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, buf);
        buf.flip();
        return buf;
    }

    private static int[] readInts(FileChannel ch, ByteBuffer chunk, int count) throws IOException {
        int[] out  = new int[count];
        int   read = 0;
        while (read < count) {
            chunk.clear();
            int wanted = Math.min(chunk.capacity() / Integer.BYTES, count - read);
            chunk.limit(wanted * Integer.BYTES);
            readFully(ch, chunk);
            chunk.flip();
            chunk.asIntBuffer().get(out, read, wanted);
            read += wanted;
        }
        return out;
    }

    private static short[] readShorts(FileChannel ch, ByteBuffer chunk, int count) throws IOException {
        short[] out  = new short[count];
        int     read = 0;
        while (read < count) {
            chunk.clear();
            int wanted = Math.min(chunk.capacity() / Short.BYTES, count - read);
            chunk.limit(wanted * Short.BYTES);
            readFully(ch, chunk);
            chunk.flip();
            chunk.asShortBuffer().get(out, read, wanted);
            read += wanted;
        }
        return out;
    }

    private static byte[] readBytes(FileChannel ch, ByteBuffer chunk, int count) throws IOException {
        byte[] out  = new byte[count];
        int    read = 0;
        while (read < count) {
            chunk.clear();
            int wanted = Math.min(chunk.capacity(), count - read);
            chunk.limit(wanted);
            readFully(ch, chunk);
            chunk.flip();
            chunk.get(out, read, wanted);
            read += wanted;
        }
        return out;
    }

    private static void readFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new IOException("unexpected EOF");
        }
    }

    private static final class Top5 {
        private long d0 = Long.MAX_VALUE;
        private long d1 = Long.MAX_VALUE;
        private long d2 = Long.MAX_VALUE;
        private long d3 = Long.MAX_VALUE;
        private long d4 = Long.MAX_VALUE;
        private int  p0 = -1;
        private int  p1 = -1;
        private int  p2 = -1;
        private int  p3 = -1;
        private int  p4 = -1;

        long worst() {
            return d4;
        }

        void add(long dist, int pos) {
            if (dist >= d4) return;
            if (dist >= d3) { d4 = dist; p4 = pos; return; }
            d4 = d3; p4 = p3;
            if (dist >= d2) { d3 = dist; p3 = pos; return; }
            d3 = d2; p3 = p2;
            if (dist >= d1) { d2 = dist; p2 = pos; return; }
            d2 = d1; p2 = p1;
            if (dist >= d0) { d1 = dist; p1 = pos; return; }
            d1 = d0; p1 = p0;
            d0 = dist; p0 = pos;
        }

        int countFrauds(byte[] labels) {
            int fc = 0;
            if (p0 >= 0) fc += labels[p0];
            if (p1 >= 0) fc += labels[p1];
            if (p2 >= 0) fc += labels[p2];
            if (p3 >= 0) fc += labels[p3];
            if (p4 >= 0) fc += labels[p4];
            return fc;
        }
    }
}
