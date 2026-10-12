package com.squadpulse.scrapingintegration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real local HTTP server standing in for the scraper's {@code POST /scrape} (KAN-68): records
 * every request and answers as the test tells it to — {@code 202 QUEUED} (the default), {@code 202
 * ALREADY_QUEUED}, any status, garbage, or never.
 */
final class FakeScraper implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One request as received. */
  record Received(
      String method, String path, String authorization, String contentType, JsonNode body) {

    String runId() {
      return body.get("runId").stringValue();
    }
  }

  /** How to answer one request. */
  @FunctionalInterface
  interface Responder {
    void respond(HttpExchange exchange, Received received) throws Exception;
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile Responder responder = queued();

  FakeScraper() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            Received request =
                new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    raw.length == 0 ? null : JSON.readTree(raw));
            received.add(request);
            responder.respond(exchange, request);
          } catch (Exception e) {
            // A hanging answer interrupted at close, or a test's own failure: just drop it.
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void answer(Responder responder) {
    this.responder = responder;
  }

  List<Received> received() {
    return List.copyOf(received);
  }

  void reset() {
    received.clear();
    responder = queued();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  // --- answers --------------------------------------------------------------------------------

  /** {@code 202 QUEUED} echoing the request's runId, as the real scraper does. */
  static Responder queued() {
    return (exchange, request) ->
        send(exchange, 202, "{\"status\":\"QUEUED\",\"runId\":" + quoted(request.runId()) + "}");
  }

  static Responder alreadyQueued(String existingRunId) {
    return (exchange, request) ->
        send(
            exchange,
            202,
            "{\"status\":\"ALREADY_QUEUED\",\"runId\":" + quoted(existingRunId) + "}");
  }

  static Responder status(int status) {
    return (exchange, request) -> send(exchange, status, "{\"error\":\"fake\"}");
  }

  static Responder body(int status, String body) {
    return (exchange, request) -> send(exchange, status, body);
  }

  /** Accepts the request and never answers (until the server closes). */
  static Responder never() {
    return (exchange, request) -> Thread.sleep(60_000);
  }

  /** Sends {@code 202} headers, then never the body. */
  static Responder headersThenNothing() {
    return (exchange, request) -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(202, 100);
      exchange.getResponseBody().flush();
      Thread.sleep(60_000);
    };
  }

  /** Runs {@code before}, then answers with {@code then}. */
  static Responder after(ThrowingRunnable before, Responder then) {
    return (exchange, request) -> {
      before.run(request);
      then.respond(exchange, request);
    };
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run(Received request) throws Exception;
  }

  private static String quoted(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
