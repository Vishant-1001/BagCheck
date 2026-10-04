package api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import model.JourneyResult;
import service.Engine;
import util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HTTP layer around the DSA engine.
 *
 * Why this design (and not a Python/Node backend):
 *   The DSA work is 100% Java, so the simplest reliable architecture is a tiny
 *   HTTP server built into the JDK (com.sun.net.httpserver). One process, no
 *   build tool, no framework, no dependency manager - the same java command that
 *   runs the tests also serves the dashboard. The engine stays free of HTTP
 *   concerns and the frontend talks to it over plain JSON.
 *
 * The executor is deliberately NOT a thread pool: handlers run one at a time on
 * the server thread, so the DSA structures need no locking inside a single
 * request and the demo output stays deterministic.
 *
 * Run:  java -cp build api.ApiServer [port] [dataDir] [frontendDir]
 */
public class ApiServer {

    private final Engine engine;
    private final Path frontendDir;

    private ApiServer(Engine engine, Path frontendDir) {
        this.engine = engine;
        this.frontendDir = frontendDir;
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String dataDir = args.length > 1 ? args[1] : "data";
        String frontendDir = args.length > 2 ? args[2] : "frontend";

        Engine engine = new Engine(dataDir);
        ApiServer app = new ApiServer(engine, Paths.get(frontendDir));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", app.new ApiHandler());
        server.createContext("/", app.new StaticHandler());
        server.setExecutor(null);   // single-threaded: no concurrent mutation of the engine
        server.start();

        System.out.println("Baggage Journey Integrity Checker");
        System.out.println("  data directory : " + dataDir);
        System.out.println("  graph profile  : " + engine.getProfileName()
                + " (" + engine.validator().getGraph().nodeCount() + " checkpoints, "
                + engine.validator().getGraph().edgeCount() + " legal transitions)");
        System.out.println("  dashboard      : http://localhost:" + port + "/");
        System.out.println("  API            : http://localhost:" + port + "/api/state");
        System.out.println("Press Ctrl+C to stop.");
    }

    // ------------------------------------------------------------------
    // JSON API
    // ------------------------------------------------------------------

    private final class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            try {
                Map<String, Object> body = readJsonBody(exchange);
                Map<String, Object> response = route(exchange, path, body);
                sendJson(exchange, 200, response);
            } catch (IllegalArgumentException badRequest) {
                Map<String, Object> error = new LinkedHashMap<String, Object>();
                error.put("ok", Boolean.FALSE);
                error.put("error", badRequest.getMessage());
                sendJson(exchange, 400, error);
            } catch (Exception failure) {
                failure.printStackTrace();
                Map<String, Object> error = new LinkedHashMap<String, Object>();
                error.put("ok", Boolean.FALSE);
                error.put("error", String.valueOf(failure));
                sendJson(exchange, 500, error);
            }
        }

        private Map<String, Object> route(HttpExchange exchange, String path, Map<String, Object> body) {
            String method = exchange.getRequestMethod();
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("ok", Boolean.TRUE);

            if (path.equals("/api/health")) {
                response.put("status", "ok");
                return response;
            }

            if (path.equals("/api/state") || path.equals("/api/graph")) {
                response.putAll(engine.statePayload());
                return response;
            }

            if (path.equals("/api/bags")) {
                response.put("bags", engine.bagsPayload());
                return response;
            }

            if (!method.equals("POST")) {
                throw new IllegalArgumentException("Method " + method + " is not supported for " + path);
            }

            if (path.equals("/api/profile")) {
                String profile = Json.string(body, "profile", "");
                engine.loadProfile(profile);
                response.put("message", "Graph profile switched to '" + profile
                        + "'. Journey state was reset because the validation rules changed.");
            } else if (path.equals("/api/scan")) {
                String bagId = Json.string(body, "bagId", "").trim();
                String checkpoint = Json.string(body, "checkpoint", "").trim();
                if (bagId.isEmpty() || checkpoint.isEmpty()) {
                    throw new IllegalArgumentException("Both bagId and checkpoint are required.");
                }
                engine.scan(bagId, checkpoint);
                response.put("message", "Enqueued " + bagId + " -> " + checkpoint);
            } else if (path.equals("/api/scans")) {
                int count = 0;
                for (Object item : Json.asArray(body.get("scans"))) {
                    Map<String, Object> scan = Json.asObject(item);
                    String bagId = Json.string(scan, "bagId", "").trim();
                    String checkpoint = Json.string(scan, "checkpoint", "").trim();
                    if (bagId.isEmpty() || checkpoint.isEmpty()) {
                        throw new IllegalArgumentException("Every scan needs a bagId and a checkpoint.");
                    }
                    engine.scan(bagId, checkpoint);
                    count++;
                }
                response.put("message", "Enqueued " + count + " scan event(s).");
            } else if (path.equals("/api/sample")) {
                int queued = engine.startSampleStream();
                response.put("message", "Loaded the sample stream (" + queued + " events) on the standard profile.");
                response.put("queued", queued);
            } else if (path.equals("/api/demo")) {
                response.putAll(engine.startDemo(Json.string(body, "id", "")));
            } else if (path.equals("/api/process")) {
                boolean all = Boolean.TRUE.equals(body.get("all"));
                List<JourneyResult> results = engine.process(all);
                response.put("results", engine.resultsPayload(results));
                if (!all && !results.isEmpty()) {
                    JourneyResult r = results.get(0);
                    System.out.println("  " + r.bagId + " -> " + r.checkpoint + " : " + r.eventResult
                            + " | journey: " + r.journeyStatus
                            + (r.anomalyType == null ? "" : " | " + r.anomalyType));
                }
            } else if (path.equals("/api/reset")) {
                engine.reset();
                response.put("message", "Queue and journey state cleared.");
            } else {
                throw new IllegalArgumentException("Unknown endpoint: " + path);
            }

            response.put("state", engine.statePayload());
            return response;
        }
    }

    // ------------------------------------------------------------------
    // Static frontend files
    // ------------------------------------------------------------------

    private final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String rawPath = exchange.getRequestURI().getPath();
            String relative = rawPath.equals("/") ? "index.html" : rawPath.substring(1);
            if (relative.contains("..")) {
                sendText(exchange, 403, "Forbidden");
                return;
            }
            Path file = frontendDir.resolve(relative).normalize();
            if (!file.startsWith(frontendDir.normalize()) || !Files.isRegularFile(file)) {
                sendText(exchange, 404, "Not found: " + relative);
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", contentType(file));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] bytes = readAll(exchange.getRequestBody());
        if (bytes.length == 0) {
            return new LinkedHashMap<String, Object>();
        }
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return Json.asObject(Json.parse(text));
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void sendJson(HttpExchange exchange, int status, Map<String, Object> payload)
            throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
