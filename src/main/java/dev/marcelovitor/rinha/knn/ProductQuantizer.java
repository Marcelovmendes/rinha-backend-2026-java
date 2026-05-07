package dev.marcelovitor.rinha.knn;

public final class ProductQuantizer {

    public static final int M             = 7;
    public static final int SUB_D         = 2;
    public static final int CODEBOOK_SIZE = 256;

    private final float[][][] codebooks;
    private final float[][]   cbFlat;

    private ProductQuantizer(float[][][] codebooks) {
        this.codebooks = codebooks;
        this.cbFlat    = flatten(codebooks);
    }

    public static ProductQuantizer train(float[][] vectors, long seed) {
        float[][][] codebooks  = new float[M][CODEBOOK_SIZE][SUB_D];
        float[][]   subVectors = new float[vectors.length][SUB_D];

        for (int m = 0; m < M; m++) {
            int offset = m * SUB_D;
            for (int i = 0; i < vectors.length; i++) {
                subVectors[i][0] = vectors[i][offset];
                subVectors[i][1] = vectors[i][offset + 1];
            }
            codebooks[m] = KMeans.clusterSub(subVectors, CODEBOOK_SIZE, seed + m);
        }
        return new ProductQuantizer(codebooks);
    }

    public static ProductQuantizer load(float[][][] codebooks) {
        return new ProductQuantizer(codebooks);
    }

    public byte[] encode(float[] vec) {
        byte[]  codes = new byte[M];
        float[] sub   = new float[SUB_D];
        for (int m = 0; m < M; m++) {
            sub[0]   = vec[m * SUB_D];
            sub[1]   = vec[m * SUB_D + 1];
            codes[m] = (byte) KMeans.nearestSub(sub, codebooks[m], SUB_D);
        }
        return codes;
    }

    public void buildAdcTable(float[] query, float[][] table) {
        for (int m = 0; m < M; m++) {
            float   q0  = query[m * SUB_D];
            float   q1  = query[m * SUB_D + 1];
            float[] cb  = cbFlat[m];
            float[] row = table[m];
            for (int c = 0; c < CODEBOOK_SIZE; c++) {
                float d0 = q0 - cb[c * 2];
                float d1 = q1 - cb[c * 2 + 1];
                row[c]   = d0 * d0 + d1 * d1;
            }
        }
    }

    public float adcDistance(float[][] table, byte[] codes, int offset) {
        return table[0][codes[offset]     & 0xFF]
             + table[1][codes[offset + 1] & 0xFF]
             + table[2][codes[offset + 2] & 0xFF]
             + table[3][codes[offset + 3] & 0xFF]
             + table[4][codes[offset + 4] & 0xFF]
             + table[5][codes[offset + 5] & 0xFF]
             + table[6][codes[offset + 6] & 0xFF];
    }

    public float[][][] codebooks() {
        return codebooks;
    }

    private static float[][] flatten(float[][][] cb) {
        int       m    = cb.length;
        int       c    = cb[0].length;
        int       d    = cb[0][0].length;
        float[][] flat = new float[m][c * d];
        for (int mm = 0; mm < m; mm++) {
            for (int cc = 0; cc < c; cc++) {
                for (int dd = 0; dd < d; dd++) {
                    flat[mm][cc * d + dd] = cb[mm][cc][dd];
                }
            }
        }
        return flat;
    }
}
