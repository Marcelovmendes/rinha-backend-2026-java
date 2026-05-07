package dev.marcelovitor.rinha;

import java.nio.charset.StandardCharsets;

public final class ReadyHandler implements RequestHandler {

    private static final byte[] RESPONSE =
        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n"
        .getBytes(StandardCharsets.US_ASCII);

    @Override
    public byte[] handle(byte[] body, int offset, int length) {
        return RESPONSE;
    }
}
