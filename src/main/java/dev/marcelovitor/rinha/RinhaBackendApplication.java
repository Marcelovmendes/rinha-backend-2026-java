package dev.marcelovitor.rinha;

import dev.marcelovitor.rinha.http.HttpServer;
import dev.marcelovitor.rinha.knn.IvfIndex;
import dev.marcelovitor.rinha.knn.Vectorizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class RinhaBackendApplication {

    private static final int    DEFAULT_PORT       = 9999;
    private static final String DEFAULT_DATA_DIR   = "/data";
    private static final int    WARMUP_ITERATIONS  = 50;
    private static final int    SYNTHETIC_WARMUPS  = 20_000;

    private static final byte[][] WARMUP_PAYLOADS = {
        """
        {"id":"warmup-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-003","MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.2331036248},"last_transaction":null}
        """.strip().getBytes(StandardCharsets.UTF_8),
        """
        {"id":"warmup-2","transaction":{"amount":6384.53,"installments":8,"requested_at":"2026-03-10T05:22:29Z"},"customer":{"avg_amount":108.4,"tx_count_24h":20,"known_merchants":["MERC-010","MERC-005","MERC-001","MERC-015"]},"merchant":{"id":"MERC-074","mcc":"7802","avg_amount":50.11},"terminal":{"is_online":true,"card_present":false,"km_from_home":271.4091990309},"last_transaction":{"timestamp":"2026-03-10T05:21:29Z","km_from_current":550.307568803}}
        """.strip().getBytes(StandardCharsets.UTF_8),
        """
        {"id":"warmup-3","transaction":{"amount":9505.97,"installments":10,"requested_at":"2026-03-14T05:15:12Z"},"customer":{"avg_amount":81.28,"tx_count_24h":20,"known_merchants":["MERC-008","MERC-007","MERC-005"]},"merchant":{"id":"MERC-068","mcc":"7802","avg_amount":54.86},"terminal":{"is_online":false,"card_present":true,"km_from_home":952.2745933273},"last_transaction":null}
        """.strip().getBytes(StandardCharsets.UTF_8),
    };

    public static void main(String[] args) throws Exception {
        int  port    = Integer.parseInt(System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        Path dataDir = Path.of(System.getenv().getOrDefault("DATA_DIR", DEFAULT_DATA_DIR));

        IvfIndex   index      = IvfIndex.load(dataDir.resolve("index.bin"));
        Vectorizer vectorizer = new Vectorizer();

        syntheticWarmup(index);
        warmup(vectorizer, index);

        new HttpServer(port, new ReadyHandler(), new ScoreHandler(vectorizer, index)).start();
    }

    private static void syntheticWarmup(IvfIndex index) {
        long    t    = System.nanoTime();
        short[] q    = new short[14];
        int     sink = 0;
        for (int i = 0; i < SYNTHETIC_WARMUPS; i++) {
            q[0]  = (short) ((i * 37) % 10000);
            q[1]  = (short) ((i * 11) % 10000);
            q[2]  = (short) ((i * 19) % 10000);
            q[3]  = (short) ((i * 23) % 10000);
            q[4]  = (short) ((i * 5)  % 10000);
            q[5]  = (short) (i % 3 == 0 ? -10000 : ((i * 7)  % 10000));
            q[6]  = (short) (i % 5 == 0 ? -10000 : ((i * 13) % 10000));
            q[7]  = (short) ((i * 29) % 10000);
            q[8]  = (short) ((i * 31) % 10000);
            q[9]  = (short) (i & 1);
            q[10] = (short) ((i >>> 1) & 1);
            q[11] = (short) ((i >>> 2) & 1);
            q[12] = (short) ((i * 17) % 10000);
            q[13] = (short) ((i * 3)  % 10000);
            sink += index.countFraudsInTop5(q);
        }
        if (sink == Integer.MIN_VALUE) throw new IllegalStateException();
        System.out.println("synthetic warmup: " + SYNTHETIC_WARMUPS + " queries in " + ((System.nanoTime() - t) / 1_000_000) + "ms");
    }

    private static void warmup(Vectorizer vectorizer, IvfIndex index) {
        long    t     = System.nanoTime();
        int     count = 0;
        short[] q     = new short[14];
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            for (byte[] payload : WARMUP_PAYLOADS) {
                try {
                    vectorizer.vectorize(payload, 0, payload.length, q);
                    index.countFraudsInTop5(q);
                    count++;
                } catch (Exception ignored) {}
            }
        }
        System.out.println("payload warmup: " + count + " queries in " + ((System.nanoTime() - t) / 1_000_000) + "ms");
    }
}
