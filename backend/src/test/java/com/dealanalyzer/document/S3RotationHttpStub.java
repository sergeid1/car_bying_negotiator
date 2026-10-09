package com.dealanalyzer.document;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only GET/PUT for the offline rotation subprocess; never uses AWS or external networking. */
final class S3RotationHttpStub implements AutoCloseable {
  private final HttpServer server;
  final java.util.List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

  S3RotationHttpStub(Path root) throws java.io.IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        String prefix = "/offline-test-bucket/";
        if (!path.startsWith(prefix)) { exchange.sendResponseHeaders(400, -1); return; }
        String key = path.substring(prefix.length());
        if (!key.matches("[A-Za-z0-9_-]+\\.enc|[A-Za-z0-9_-]+\\.png\\.enc")) {
          exchange.sendResponseHeaders(400, -1); return;
        }
        Path object = root.resolve(key);
        if (exchange.getRequestMethod().equals("GET")) {
          if (!Files.exists(object)) { exchange.sendResponseHeaders(404, -1); return; }
          byte[] bytes = Files.readAllBytes(object);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
        } else if (exchange.getRequestMethod().equals("PUT")) {
          if (!"AES256".equals(exchange.getRequestHeaders().getFirst("x-amz-server-side-encryption"))) {
            exchange.sendResponseHeaders(400, -1); return;
          }
          byte[] body = exchange.getRequestBody().readAllBytes();
          String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
          Files.write(object, encoding != null && encoding.contains("aws-chunked") ? decodeChunks(body) : body);
          exchange.sendResponseHeaders(200, -1);
        } else exchange.sendResponseHeaders(405, -1);
      }
    });
    server.start();
  }

  String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
  @Override public void close() { server.stop(0); }

  private static byte[] decodeChunks(byte[] body) throws java.io.IOException {
    var decoded = new java.io.ByteArrayOutputStream();
    int cursor = 0;
    while (cursor < body.length) {
      int end = cursor;
      while (end + 1 < body.length && !(body[end] == '\r' && body[end + 1] == '\n')) end++;
      if (end + 1 >= body.length) throw new java.io.IOException("Invalid fixture chunk framing");
      String line = new String(body, cursor, end - cursor, java.nio.charset.StandardCharsets.US_ASCII);
      int length = Integer.parseInt(line.split(";", 2)[0], 16);
      if (length == 0) return decoded.toByteArray();
      cursor = end + 2;
      if (length < 0 || length > body.length - cursor - 2) throw new java.io.IOException("Invalid fixture chunk size");
      decoded.write(body, cursor, length);
      cursor += length + 2;
    }
    throw new java.io.IOException("Missing fixture chunk terminator");
  }
}
