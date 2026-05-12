package dev.marcelovitor.rinha.knn;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

public final class ReferenceCatalog {

    private static final int    DIMS         = IndexHeader.DIMS;
    private static final int    SCALE        = IndexHeader.SCALE;
    private static final int    GZIP_BUFFER  = 1 << 16;
    private static final int    BUFFERED_IN  = 1 << 16;
    private static final int    GROW_GUESS_N = 3_000_000;
    private static final int    SEED_CAP     = 1024;
    private static final byte[] TOKEN_VECTOR = "\"vector\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TOKEN_LABEL  = "\"label\"".getBytes(StandardCharsets.US_ASCII);

    private final int       count;
    private final short[][] dims;
    private final byte[]    labels;

    private ReferenceCatalog(int count, short[][] dims, byte[] labels) {
        this.count  = count;
        this.dims   = dims;
        this.labels = labels;
    }

    public static ReferenceCatalog load(Path path) throws IOException {
        boolean gz       = path.toString().endsWith(".gz");
        int     capacity = gz ? GROW_GUESS_N : SEED_CAP;
        Builder builder  = new Builder(capacity, !gz);

        try (InputStream raw   = Files.newInputStream(path);
             InputStream maybe = gz ? new GZIPInputStream(raw, GZIP_BUFFER) : raw;
             BufferedInputStream in = new BufferedInputStream(maybe, BUFFERED_IN)) {
            parseStream(in, builder);
        }
        return builder.build();
    }

    public int count() {
        return count;
    }

    public short[] dim(int d) {
        return dims[d];
    }

    public byte[] labels() {
        return labels;
    }

    private static void parseStream(BufferedInputStream in, Builder builder) throws IOException {
        short[] vector = new short[DIMS];
        while (advanceTo(in, TOKEN_VECTOR)) {
            skipUntil(in, '[');
            for (int d = 0; d < DIMS; d++) {
                vector[d] = quantize(readNumber(in));
            }
            if (!advanceTo(in, TOKEN_LABEL)) break;
            skipUntil(in, ':');
            skipUntil(in, '"');
            int  first = in.read();
            byte label = (byte) (first == 'f' ? 1 : 0);
            skipUntil(in, '"');
            builder.add(vector, label);
        }
    }

    private static boolean advanceTo(BufferedInputStream in, byte[] needle) throws IOException {
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            if (b == needle[matched]) {
                matched++;
                if (matched == needle.length) return true;
            } else {
                matched = b == needle[0] ? 1 : 0;
            }
        }
        return false;
    }

    private static void skipUntil(BufferedInputStream in, int target) throws IOException {
        int b;
        while ((b = in.read()) >= 0) {
            if (b == target) return;
        }
        throw new IOException("unexpected EOF");
    }

    private static double readNumber(BufferedInputStream in) throws IOException {
        int b;
        do {
            b = in.read();
            if (b < 0) throw new IOException("unexpected EOF");
        } while (b != '-' && (b < '0' || b > '9'));

        boolean negative = b == '-';
        if (negative) b = in.read();

        long integral = 0;
        while (b >= '0' && b <= '9') {
            integral = integral * 10 + (b - '0');
            b = in.read();
        }

        double value = integral;
        if (b == '.') {
            double divisor = 10.0;
            b = in.read();
            while (b >= '0' && b <= '9') {
                value   += (b - '0') / divisor;
                divisor *= 10.0;
                b        = in.read();
            }
        }
        return negative ? -value : value;
    }

    private static short quantize(double value) {
        return (short) Math.round(value * SCALE);
    }

    private static final class Builder {

        private short[][]     dims;
        private byte[]        labels;
        private int           count;
        private final boolean growable;

        Builder(int capacity, boolean growable) {
            this.growable = growable;
            this.dims     = new short[DIMS][capacity];
            this.labels   = new byte[capacity];
        }

        void add(short[] vector, byte label) {
            if (count == labels.length) {
                if (!growable) throw new IllegalStateException("capacity exceeded");
                int next = labels.length * 2;
                labels = Arrays.copyOf(labels, next);
                for (int d = 0; d < DIMS; d++) dims[d] = Arrays.copyOf(dims[d], next);
            }
            for (int d = 0; d < DIMS; d++) dims[d][count] = vector[d];
            labels[count] = label;
            count++;
        }

        ReferenceCatalog build() {
            if (growable) {
                labels = Arrays.copyOf(labels, count);
                for (int d = 0; d < DIMS; d++) dims[d] = Arrays.copyOf(dims[d], count);
            }
            return new ReferenceCatalog(count, dims, labels);
        }
    }
}
