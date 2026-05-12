package dev.marcelovitor.rinha;

import dev.marcelovitor.rinha.http.HttpServer;
import dev.marcelovitor.rinha.knn.IvfIndex;
import dev.marcelovitor.rinha.knn.Vectorizer;

import java.nio.file.Path;

public final class RinhaBackendApplication {

    private static final int    DEFAULT_PORT      = 9999;
    private static final String DEFAULT_INDEX     = "/app/resources/index.bin";
    private static final int    DEFAULT_WARMUP    = 20_000;
    private static final int    QUERY_DIMS        = 14;
    private static final int    WARMUP_MOD        = 10_000;

    public static void main(String[] args) throws Exception {
        int  port      = Integer.parseInt(System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        Path indexPath = Path.of(System.getenv().getOrDefault("INDEX_PATH", DEFAULT_INDEX));

        long t0 = System.nanoTime();
        IvfIndex   index      = IvfIndex.load(indexPath);
        Vectorizer vectorizer = new Vectorizer();
        long loadMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("loaded index from " + indexPath + " size=" + index.size() + " loadMs=" + loadMs);

        if (index.size() <= 0) throw new IllegalStateException("empty index");

        int  warmupSearches = Integer.getInteger("startup.warmup.searches", DEFAULT_WARMUP);
        long w0             = System.nanoTime();
        primeIndex(index, warmupSearches);
        long warmupMs = (System.nanoTime() - w0) / 1_000_000L;
        System.out.println("warmup searches=" + warmupSearches + " warmupMs=" + warmupMs);

        new HttpServer(port, new ReadyHandler(), new ScoreHandler(vectorizer, index)).start();
    }

    private static void primeIndex(IvfIndex index, int iterations) {
        if (iterations <= 0) return;
        short[] query = new short[QUERY_DIMS];
        int     sink  = 0;
        for (int i = 0; i < iterations; i++) {
            seedQuery(query, i);
            sink += index.topKFraudCount(query);
        }
        if (sink == Integer.MIN_VALUE) throw new IllegalStateException();
    }

    private static void seedQuery(short[] q, int i) {
        q[0]  = (short) ((i * 37) % WARMUP_MOD);
        q[1]  = (short) ((i * 11) % WARMUP_MOD);
        q[2]  = (short) ((i * 19) % WARMUP_MOD);
        q[3]  = (short) ((i * 23) % WARMUP_MOD);
        q[4]  = (short) ((i * 5)  % WARMUP_MOD);
        q[5]  = (short) (i % 3 == 0 ? -WARMUP_MOD : ((i * 7)  % WARMUP_MOD));
        q[6]  = (short) (i % 5 == 0 ? -WARMUP_MOD : ((i * 13) % WARMUP_MOD));
        q[7]  = (short) ((i * 29) % WARMUP_MOD);
        q[8]  = (short) ((i * 31) % WARMUP_MOD);
        q[9]  = (short) (i & 1);
        q[10] = (short) ((i >>> 1) & 1);
        q[11] = (short) ((i >>> 2) & 1);
        q[12] = (short) ((i * 17) % WARMUP_MOD);
        q[13] = (short) ((i * 3)  % WARMUP_MOD);
    }
}
