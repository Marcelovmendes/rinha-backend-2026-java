package dev.marcelovitor.rinha.http;

import dev.marcelovitor.rinha.RequestHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public final class HttpServer {

    private static final System.Logger LOGGER          = System.getLogger(HttpServer.class.getName());
    private static final int           READ_BUFFER_SIZE = 8192;
    private static final String        PATH_READY       = "/ready";
    private static final String        PATH_FRAUD_SCORE = "/fraud-score";
    private static final byte[]        NOT_FOUND        = buildPlainResponse(404, "Not Found");

    private final int            port;
    private final RequestHandler readyHandler;
    private final RequestHandler scoreHandler;

    public HttpServer(int port, RequestHandler readyHandler, RequestHandler scoreHandler) {
        this.port         = port;
        this.readyHandler = readyHandler;
        this.scoreHandler = scoreHandler;
    }

    public void start() throws IOException {
        String              socketPath    = System.getenv("SERVER_SOCKET_PATH");
        ServerSocketChannel serverChannel;
        if (socketPath != null) {
            Files.deleteIfExists(Path.of(socketPath));
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(Path.of(socketPath), PosixFilePermissions.fromString("rw-rw-rw-"));
            LOGGER.log(System.Logger.Level.INFO, "Listening on unix socket {0}", socketPath);
        } else {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            LOGGER.log(System.Logger.Level.INFO, "Listening on port {0}", port);
        }

        while (true) {
            SocketChannel clientChannel = serverChannel.accept();
            Thread.ofVirtual().start(() -> handleConnection(clientChannel));
        }
    }

    private void handleConnection(SocketChannel channel) {
        try (channel) {
            byte[] readBuffer = new byte[READ_BUFFER_SIZE];
            int filled = 0;

            while (true) {
                int bytesRead = channel.read(ByteBuffer.wrap(readBuffer, filled, readBuffer.length - filled));
                if (bytesRead < 0) return;
                filled += bytesRead;

                filled = processRequests(channel, readBuffer, filled);
                if (filled < 0) return;
                if (filled == readBuffer.length) return;
            }
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.DEBUG, "Connection closed: {0}", e.getMessage());
        }
    }

    private int processRequests(SocketChannel channel, byte[] readBuffer, int filled) throws IOException {
        while (true) {
            ParsedRequest request = HttpRequestParser.tryParse(readBuffer, filled);
            if (request == null) return filled;

            HttpResponseWriter.write(channel, route(request, readBuffer));

            filled = shiftUnconsumedBytes(readBuffer, filled, request.totalLen());

            if (!request.keepAlive()) return -1;
        }
    }

    private byte[] route(ParsedRequest request, byte[] readBuffer) {
        if (request.method() == HttpMethod.GET && pathMatches(readBuffer, request, PATH_READY)) {
            return readyHandler.handle(readBuffer, request.bodyStart(), request.bodyLen());
        }
        if (request.method() == HttpMethod.POST && pathMatches(readBuffer, request, PATH_FRAUD_SCORE)) {
            return scoreHandler.handle(readBuffer, request.bodyStart(), request.bodyLen());
        }
        return NOT_FOUND;
    }

    private static int shiftUnconsumedBytes(byte[] buf, int filled, int consumed) {
        int remaining = filled - consumed;
        if (remaining > 0) {
            System.arraycopy(buf, consumed, buf, 0, remaining);
        }
        return remaining;
    }

    private static boolean pathMatches(byte[] buf, ParsedRequest request, String path) {
        if (request.pathLen() != path.length()) return false;
        for (int i = 0; i < request.pathLen(); i++) {
            if (buf[request.pathStart() + i] != (byte) path.charAt(i)) return false;
        }
        return true;
    }

    private static byte[] buildPlainResponse(int statusCode, String statusText) {
        String raw = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n";
        return raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
