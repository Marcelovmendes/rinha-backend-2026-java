package dev.marcelovitor.rinha.knn;

import java.nio.ByteBuffer;

public record IndexHeader(int n, int k, int dims, int scale) {

    public static final int MAGIC   = 0x49565831;
    public static final int VERSION = 1;
    public static final int DIMS    = 14;
    public static final int SCALE   = 10_000;
    public static final int BYTES   = 6 * Integer.BYTES;

    public static IndexHeader read(ByteBuffer buf) {
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalStateException("invalid index magic: 0x" + Integer.toHexString(magic));
        }
        int version = buf.getInt();
        if (version != VERSION) {
            throw new IllegalStateException("unsupported index version: " + version);
        }
        return new IndexHeader(buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt());
    }

    public void write(ByteBuffer buf) {
        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(n);
        buf.putInt(k);
        buf.putInt(dims);
        buf.putInt(scale);
    }
}
