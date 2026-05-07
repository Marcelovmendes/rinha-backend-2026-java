package dev.marcelovitor.rinha;

public interface RequestHandler {
    byte[] handle(byte[] body, int offset, int length);
}
