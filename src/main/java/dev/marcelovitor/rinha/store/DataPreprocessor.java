package dev.marcelovitor.rinha.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

public final class DataPreprocessor {

    private static final int DIMENSIONS        = 14;
    private static final int BYTES_PER_FLOAT   = 4;
    private static final int BITS_PER_BYTE     = 8;
    private static final int READ_BUFFER_SIZE  = 1 << 16;
    private static final int WRITE_BUFFER_SIZE = 1 << 20;
    private static final int PROGRESS_INTERVAL = 500_000;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: DataPreprocessor <references.json.gz> <output-dir>");
            System.exit(1);
        }

        Path inputFile = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        Files.createDirectories(outputDir);

        long start   = System.currentTimeMillis();
        int  count   = process(inputFile, outputDir);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("Done: %,d records in %,d ms%n", count, elapsed);
        System.out.printf("references.bin : %,d bytes%n", (long) count * DIMENSIONS * BYTES_PER_FLOAT);
        System.out.printf("labels.bin     : %,d bytes%n", (count + 7) / 8);

        IvfBuilder.build(outputDir);
    }

    private static int process(Path inputFile, Path outputDir) throws IOException {
        try (
            InputStream raw   = new BufferedInputStream(Files.newInputStream(inputFile), READ_BUFFER_SIZE);
            InputStream gz    = new GZIPInputStream(raw, READ_BUFFER_SIZE);
            JsonParser parser = new ObjectMapper().getFactory().createParser(gz);
            OutputStream refs = new BufferedOutputStream(Files.newOutputStream(outputDir.resolve("references.bin")), WRITE_BUFFER_SIZE);
            OutputStream lbls = new BufferedOutputStream(Files.newOutputStream(outputDir.resolve("labels.bin")), READ_BUFFER_SIZE)
        ) {
            return writeRecords(parser, refs, lbls);
        }
    }

    private static int writeRecords(JsonParser parser, OutputStream refs, OutputStream lbls) throws IOException {
        byte[]     vectorBuffer = new byte[DIMENSIONS * BYTES_PER_FLOAT];
        ByteBuffer byteBuffer   = ByteBuffer.wrap(vectorBuffer).order(ByteOrder.LITTLE_ENDIAN);
        int        labelByte    = 0;
        int        bitPosition  = 0;
        int        count        = 0;

        expectToken(parser, JsonToken.START_ARRAY);

        while (parser.nextToken() == JsonToken.START_OBJECT) {
            ReferenceRecord record = parseRecord(parser, count);

            writeVector(record.vector(), byteBuffer, vectorBuffer, refs);
            labelByte  = packLabelBit(record.fraud(), labelByte, bitPosition, lbls);
            bitPosition = (bitPosition + 1) % BITS_PER_BYTE;

            if (bitPosition == 0) {
                labelByte = 0;
            }

            count++;
            if (count % PROGRESS_INTERVAL == 0) {
                System.out.printf("  %,d records processed...%n", count);
            }
        }

        if (bitPosition > 0) {
            lbls.write(labelByte);
        }

        return count;
    }

    private static ReferenceRecord parseRecord(JsonParser parser, int recordIndex) throws IOException {
        float[] vector = null;
        boolean fraud  = false;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "vector" -> vector = readFloatArray(parser);
                case "label"  -> fraud  = "fraud".equals(parser.getText());
                default       -> parser.skipChildren();
            }
        }

        if (vector == null) {
            throw new IllegalStateException("Missing vector field at record " + recordIndex);
        }

        return new ReferenceRecord(vector, fraud);
    }

    private static void writeVector(float[] vector, ByteBuffer byteBuffer, byte[] vectorBuffer, OutputStream refs) throws IOException {
        byteBuffer.rewind();
        for (float v : vector) {
            byteBuffer.putFloat(v);
        }
        refs.write(vectorBuffer);
    }

    private static int packLabelBit(boolean fraud, int labelByte, int bitPosition, OutputStream lbls) throws IOException {
        int updated = fraud ? labelByte | (1 << (BITS_PER_BYTE - 1 - bitPosition)) : labelByte;
        if (bitPosition == BITS_PER_BYTE - 1) {
            lbls.write(updated);
        }
        return updated;
    }

    private static float[] readFloatArray(JsonParser parser) throws IOException {
        float[] values = new float[DIMENSIONS];
        int i = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            values[i++] = parser.getFloatValue();
        }
        return values;
    }

    private static void expectToken(JsonParser parser, JsonToken expected) throws IOException {
        JsonToken actual = parser.nextToken();
        if (actual != expected) {
            throw new IllegalStateException("Expected token " + expected + " but found " + actual);
        }
    }

    private record ReferenceRecord(float[] vector, boolean fraud) {}
}
