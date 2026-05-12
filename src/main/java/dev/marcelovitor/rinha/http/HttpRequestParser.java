package dev.marcelovitor.rinha.http;

public final class HttpRequestParser {

    private static final String PREFIX_GET             = "GET ";
    private static final String PREFIX_POST            = "POST ";
    private static final String HEADER_CONTENT_LENGTH  = "content-length: ";
    private static final String HEADER_CONNECTION      = "connection: ";
    private static final String VALUE_CONNECTION_CLOSE = "close";

    private HttpRequestParser() {}

    public static ParsedRequest tryParse(byte[] buf, int filled) {
        int lineEnd = indexOfCrLf(buf, 0, filled);
        if (lineEnd < 0) return null;

        HttpMethod method  = detectMethod(buf);
        int pathStart      = detectPathStart(buf, method, lineEnd);
        int pathEnd        = detectPathEnd(buf, pathStart, lineEnd);
        int headerEnd      = indexOfDoubleNewline(buf, lineEnd + 2, filled);
        if (headerEnd < 0) return null;

        ParsedHeaders headers = parseHeaders(buf, lineEnd + 2, headerEnd);
        int bodyStart  = headerEnd;
        int requestEnd = bodyStart + headers.contentLength();
        if (requestEnd > filled) return null;

        return new ParsedRequest(
            method,
            pathStart,
            pathEnd - pathStart,
            bodyStart,
            headers.contentLength(),
            requestEnd,
            !headers.connectionClose()
        );
    }

    private static HttpMethod detectMethod(byte[] buf) {
        if (startsWith(buf, 0, PREFIX_GET)) return HttpMethod.GET;
        if (startsWith(buf, 0, PREFIX_POST)) return HttpMethod.POST;
        return HttpMethod.UNKNOWN;
    }

    private static int detectPathStart(byte[] buf, HttpMethod method, int lineEnd) {
        if (method == HttpMethod.GET) return PREFIX_GET.length();
        if (method == HttpMethod.POST) return PREFIX_POST.length();
        int methodEnd = indexOfByte(buf, 0, lineEnd, (byte) ' ');
        return (methodEnd >= 0) ? methodEnd + 1 : 0;
    }

    private static int detectPathEnd(byte[] buf, int pathStart, int lineEnd) {
        int pathEnd = indexOfByte(buf, pathStart, lineEnd, (byte) ' ');
        return (pathEnd >= 0) ? pathEnd : lineEnd;
    }

    private record ParsedHeaders(int contentLength, boolean connectionClose) {}

    private static ParsedHeaders parseHeaders(byte[] buf, int from, int headerEnd) {
        int contentLength   = 0;
        boolean connClose   = false;
        int pos             = from;

        while (pos < headerEnd - 2) {
            int lineEnd = indexOfCrLf(buf, pos, headerEnd);
            if (lineEnd < 0) break;
            if (startsWithIgnoreCase(buf, pos, HEADER_CONTENT_LENGTH)) {
                contentLength = parseDecimal(buf, pos + HEADER_CONTENT_LENGTH.length(), lineEnd);
            }
            if (startsWithIgnoreCase(buf, pos, HEADER_CONNECTION)) {
                connClose = startsWithIgnoreCase(buf, pos + HEADER_CONNECTION.length(), VALUE_CONNECTION_CLOSE);
            }
            pos = lineEnd + 2;
        }

        return new ParsedHeaders(contentLength, connClose);
    }

    private static int indexOfCrLf(byte[] buf, int from, int to) {
        for (int i = from; i < to - 1; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') return i;
        }
        return -1;
    }

    private static int indexOfDoubleNewline(byte[] buf, int from, int to) {
        for (int i = from; i <= to - 4; i++) {
            if (buf[i] == '\r' && buf[i+1] == '\n' && buf[i+2] == '\r' && buf[i+3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private static int indexOfByte(byte[] buf, int from, int to, byte target) {
        for (int i = from; i < to; i++) {
            if (buf[i] == target) return i;
        }
        return -1;
    }

    private static boolean startsWith(byte[] buf, int start, String prefix) {
        int len = prefix.length();
        if (start + len > buf.length) return false;
        for (int i = 0; i < len; i++) {
            if (buf[start + i] != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }

    private static boolean startsWithIgnoreCase(byte[] buf, int start, String prefix) {
        int len = prefix.length();
        if (start + len > buf.length) return false;
        for (int i = 0; i < len; i++) {
            int b = buf[start + i] & 0xFF;
            if (b >= 'A' && b <= 'Z') b += 32;
            if (b != (byte) prefix.charAt(i)) return false;
        }
        return true;
    }

    private static int parseDecimal(byte[] buf, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            int digit = buf[i] - '0';
            if (digit < 0 || digit > 9) break;
            value = value * 10 + digit;
        }
        return value;
    }
}
