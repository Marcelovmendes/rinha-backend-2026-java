package dev.marcelovitor.rinha;

import dev.marcelovitor.rinha.http.HttpServer;
import dev.marcelovitor.rinha.knn.FraudDetector;
import dev.marcelovitor.rinha.knn.IndexBinReader;
import dev.marcelovitor.rinha.knn.Vectorizer;

import java.nio.file.Path;

public final class RinhaBackendApplication {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9999"));
        Path indexPath = IndexBinReader.defaultPath();

        long t0 = System.nanoTime();
        FraudDetector detector = IndexBinReader.readFrom(indexPath);
        Vectorizer vectorizer = new Vectorizer();
        long loadMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("loaded index from " + indexPath + " size=" + detector.size() + " loadMs=" + loadMs);

        int warmupSearches = Integer.getInteger("startup.warmup.searches", 20000);
        long w0 = System.nanoTime();
        warmupIndex(detector, warmupSearches);
        long warmupMs = (System.nanoTime() - w0) / 1_000_000L;
        System.out.println("warmup searches=" + warmupSearches + " warmupMs=" + warmupMs);

        if (detector.size() <= 0) throw new IllegalStateException("empty index");

        new HttpServer(port, new ReadyHandler(), new ScoreHandler(vectorizer, detector)).start();
    }

    private static void warmupIndex(FraudDetector detector, int searches) {
        if (searches <= 0) return;
        short[] q = new short[14];
        int sink = 0;
        for (int i = 0; i < searches; i++) {
            q[0] = (short) ((i * 37) % 10000);
            q[1] = (short) ((i * 11) % 10000);
            q[2] = (short) ((i * 19) % 10000);
            q[3] = (short) ((i * 23) % 10000);
            q[4] = (short) ((i * 5) % 10000);
            q[5] = (short) (i % 3 == 0 ? -10000 : ((i * 7) % 10000));
            q[6] = (short) (i % 5 == 0 ? -10000 : ((i * 13) % 10000));
            q[7] = (short) ((i * 29) % 10000);
            q[8] = (short) ((i * 31) % 10000);
            q[9] = (short) (i & 1);
            q[10] = (short) ((i >>> 1) & 1);
            q[11] = (short) ((i >>> 2) & 1);
            q[12] = (short) ((i * 17) % 10000);
            q[13] = (short) ((i * 3) % 10000);
            sink += detector.topKFraudCount(q);
        }
        if (sink == 42) throw new IllegalStateException();
    }
}
