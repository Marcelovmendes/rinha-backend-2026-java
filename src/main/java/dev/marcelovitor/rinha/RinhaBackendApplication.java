package dev.marcelovitor.rinha;

import dev.marcelovitor.rinha.http.HttpServer;
import dev.marcelovitor.rinha.knn.IvfIndex;
import dev.marcelovitor.rinha.knn.Vectorizer;
import dev.marcelovitor.rinha.knn.VectorSearcher;

import java.nio.file.Path;

public final class RinhaBackendApplication {

    private static final int    DEFAULT_PORT     = 9999;
    private static final String DEFAULT_DATA_DIR = "/data";

    public static void main(String[] args) throws Exception {
        int  port    = Integer.parseInt(System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT)));
        Path dataDir = Path.of(System.getenv().getOrDefault("DATA_DIR", DEFAULT_DATA_DIR));

        IvfIndex       index      = IvfIndex.load(dataDir);
        Vectorizer     vectorizer = new Vectorizer();
        VectorSearcher searcher   = new VectorSearcher(index);

        new HttpServer(port, new ReadyHandler(), new ScoreHandler(vectorizer, searcher)).start();
    }
}
