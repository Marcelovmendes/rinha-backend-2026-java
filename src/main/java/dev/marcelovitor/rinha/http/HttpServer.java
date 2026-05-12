package dev.marcelovitor.rinha.http;

import dev.marcelovitor.rinha.RequestHandler;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public final class HttpServer {

    private static final System.Logger LOGGER             = System.getLogger(HttpServer.class.getName());
    private static final byte[]        GET_READY          = "GET /ready ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[]        POST_FRAUD_SCORE   = "POST /fraud-score ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[]        CONTENT_LENGTH     = "content-length:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[]        NOT_FOUND          = buildPlainResponse(404, "Not Found");
    private static final byte[]        PAYLOAD_TOO_LARGE  = buildPlainResponse(413, "Payload Too Large");
    private static final int           HEADER_MAX         = Integer.getInteger("server.header.max", 2048);
    private static final int           BODY_MAX           = Integer.getInteger("server.body.max", 8192);
    private static final int           INBUF              = Integer.getInteger("server.inbuf", 4096);
    private static final int           OUTBUF             = Integer.getInteger("server.outbuf", 4096);
    private static final int           BACKLOG            = Integer.getInteger("server.backlog", 8192);

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
            serverChannel.bind(UnixDomainSocketAddress.of(socketPath), BACKLOG);
            Files.setPosixFilePermissions(Path.of(socketPath), PosixFilePermissions.fromString("rw-rw-rw-"));
            LOGGER.log(System.Logger.Level.INFO, "Listening on unix socket {0}", socketPath);
        } else {
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port), BACKLOG);
            LOGGER.log(System.Logger.Level.INFO, "Listening on port {0}", port);
        }

        while (true) {
            SocketChannel clientChannel = serverChannel.accept();
            Thread.startVirtualThread(() -> handleConnection(clientChannel));
        }
    }

    private void handleConnection(SocketChannel channel) {
        try (channel;
             InputStream  in  = new BufferedInputStream(Channels.newInputStream(channel),  INBUF);
             OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), OUTBUF)) {
            handleStreams(in, out);
        } catch (IOException ignored) {
        }
    }

    private void handleStreams(InputStream in, OutputStream out) throws IOException {
        byte[] header = new byte[HEADER_MAX];
        byte[] body   = new byte[BODY_MAX];

        while (true) {
            int headerLen = readHeaders(in, header);
            if (headerLen <= 0) return;

            boolean isReady = startsWith(header, headerLen, GET_READY);
            boolean isScore = startsWith(header, headerLen, POST_FRAUD_SCORE);

            int contentLength = contentLength(header, headerLen);
            if (contentLength > body.length) {
                drain(in, contentLength);
                out.write(PAYLOAD_TOO_LARGE);
                out.flush();
                continue;
            }
            if (contentLength > 0) readFully(in, body, contentLength);

            if (isReady) {
                out.write(readyHandler.handle(body, 0, contentLength));
            } else if (isScore) {
                out.write(scoreHandler.handle(body, 0, contentLength));
            } else {
                out.write(NOT_FOUND);
            }
            out.flush();
        }
    }

    private static int readHeaders(InputStream in, byte[] buf) throws IOException {
        int n     = 0;
        int state = 0;
        while (n < buf.length) {
            int b = in.read();
            if (b < 0) return n == 0 ? -1 : n;
            buf[n++] = (byte) b;
            state = switch (state) {
                case 0  -> b == '\r' ? 1 : 0;
                case 1  -> b == '\n' ? 2 : 0;
                case 2  -> b == '\r' ? 3 : 0;
                case 3  -> b == '\n' ? 4 : 0;
                default -> state;
            };
            if (state == 4) return n;
        }
        return -1;
    }

    private static int contentLength(byte[] header, int len) {
        byte[] key = CONTENT_LENGTH;
        for (int i = 0; i <= len - key.length; i++) {
            int j = 0;
            while (j < key.length) {
                byte hb = header[i + j];
                if (hb >= 'A' && hb <= 'Z') hb = (byte) (hb + 32);
                if (hb != key[j]) break;
                j++;
            }
            if (j == key.length) {
                int p = i + key.length;
                while (p < len && (header[p] == ' ' || header[p] == '\t')) p++;
                int v = 0;
                while (p < len && header[p] >= '0' && header[p] <= '9') {
                    v = v * 10 + (header[p++] - '0');
                }
                return v;
            }
        }
        return 0;
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new IOException("unexpected EOF");
            off += r;
        }
    }

    private static void drain(InputStream in, int len) throws IOException {
        byte[] tmp  = new byte[1024];
        int    left = len;
        while (left > 0) {
            int r = in.read(tmp, 0, Math.min(tmp.length, left));
            if (r < 0) return;
            left -= r;
        }
    }

    private static boolean startsWith(byte[] a, int len, byte[] s) {
        if (len < s.length) return false;
        for (int i = 0; i < s.length; i++) {
            if (a[i] != s[i]) return false;
        }
        return true;
    }

    private static byte[] buildPlainResponse(int statusCode, String statusText) {
        String raw = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                   + "Content-Length: 0\r\n"
                   + "Connection: keep-alive\r\n"
                   + "\r\n";
        return raw.getBytes(StandardCharsets.US_ASCII);
    }
}
