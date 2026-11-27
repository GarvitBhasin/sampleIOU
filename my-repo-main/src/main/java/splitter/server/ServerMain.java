package splitter.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import splitter.server.controller.*;
import splitter.server.model.*;
import splitter.server.persistence.DatabaseConfig;
import splitter.server.persistence.PostgresDatabase;
import splitter.server.persistence.PostgresGroupRepository;
import splitter.server.persistence.PostgresUserRepository;
import splitter.shared.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * Main HTTP server application: initializes controllers, handles HTTP endpoints,
 * and serves the responsive web interface.
 */
public class ServerMain {
    private final int port;
    private final PostgresDatabase postgresDatabase;
    private HttpServer server;

    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final TokenGenerator tokenGenerator;
    private final GroupCodeGenerator groupCodeGenerator;
    private final GroupLockManager lockManager;
    private final AuthGuard authGuard;
    private final UserApiController userApiController;
    private final GroupApiController groupApiController;
    private final BillApiController billApiController;

    public ServerMain() {
        this(configuredPort(), Path.of("data"), DatabaseConfig.fromEnvironment(), true);
    }

    /** Uses DATABASE_URL when configured; otherwise uses local file storage for development. */
    public ServerMain(int port) {
        this(port, Path.of("data"), DatabaseConfig.fromEnvironment(), true);
    }

    /** Explicit file-backed constructor retained for isolated unit tests and local development. */
    public ServerMain(int port, Path dataDir) {
        this(port, dataDir, null, false);
    }

    private ServerMain(int port, Path dataDir, DatabaseConfig databaseConfig, boolean useEnvironment) {
        this.port = port;
        boolean databaseRequired = Boolean.parseBoolean(System.getenv().getOrDefault("IOU_REQUIRE_DATABASE", "false"));
        if (useEnvironment && databaseRequired && databaseConfig == null) {
            throw new IllegalStateException("DATABASE_URL is required but was not configured");
        }

        if (databaseConfig != null) {
            this.postgresDatabase = new PostgresDatabase(databaseConfig);
            this.userRepository = new PostgresUserRepository(postgresDatabase);
            this.groupRepository = new PostgresGroupRepository(postgresDatabase);
        } else {
            this.postgresDatabase = null;
            Path usersDb = dataDir != null ? dataDir.resolve("users.db") : null;
            Path groupsDb = dataDir != null ? dataDir.resolve("groups.db") : null;
            this.userRepository = new UserRepository(usersDb);
            this.groupRepository = new GroupRepository(groupsDb);
        }

        this.tokenGenerator = new TokenGenerator();
        this.groupCodeGenerator = new GroupCodeGenerator();
        this.lockManager = new GroupLockManager();
        this.authGuard = new AuthGuard(userRepository, groupRepository);
        this.userApiController = new UserApiController(userRepository, tokenGenerator);
        this.groupApiController = new GroupApiController(authGuard, groupRepository, groupCodeGenerator, lockManager);
        this.billApiController = new BillApiController(authGuard, groupRepository, new SplitCalculator(), lockManager);
    }

    public void start() throws IOException {
        if (postgresDatabase != null) {
            postgresDatabase.initializeSchema();
            System.out.println("Connected to PostgreSQL database");
        } else {
            System.out.println("Using local file storage (set DATABASE_URL to use PostgreSQL)");
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", this::handleHealth);

        server.createContext("/api/user/create", exchange -> handlePost(exchange, body -> {
            String username = (String) body.get("username");
            return userApiController.createUser(username);
        }));

        server.createContext("/api/group/list", exchange -> handlePost(exchange, body -> {
            Credentials creds = extractCredentials(body);
            return groupApiController.listGroups(creds);
        }));

        server.createContext("/api/group/create", exchange -> handlePost(exchange, body -> {
            Credentials creds = extractCredentials(body);
            String gName = (String) body.get("groupName");
            String mName = (String) body.get("memberName");
            return groupApiController.createGroup(creds, gName, mName);
        }));

        server.createContext("/api/group/join", exchange -> handlePost(exchange, body -> {
            Credentials creds = extractCredentials(body);
            String code = (String) body.get("groupCode");
            String mName = (String) body.get("memberName");
            return groupApiController.joinGroup(creds, code, mName);
        }));

        server.createContext("/api/group/get", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            return groupApiController.getGroup(access);
        }));

        server.createContext("/api/group/rename", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            String newName = (String) body.get("newName");
            return groupApiController.renameGroup(access, newName);
        }));

        server.createContext("/api/group/generate-code", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            return groupApiController.generateNewCode(access);
        }));

        server.createContext("/api/group/leave", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            return groupApiController.leaveGroup(access);
        }));

        server.createContext("/api/bill/create", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            @SuppressWarnings("unchecked")
            Map<String, Object> billMap = (Map<String, Object>) body.get("bill");
            String desc = (String) billMap.get("description");
            long total = ((Number) billMap.get("totalCents")).longValue();
            String dateStr = (String) billMap.get("suggestedDate");
            Result<LocalDate> parsedDate = InputValidator.parseOptionalDate(dateStr);
            if (!parsedDate.isSuccess()) return Result.fail(parsedDate.getFailureReason());
            LocalDate date = parsedDate.getData();
            @SuppressWarnings("unchecked")
            Map<String, Object> rawWeights = (Map<String, Object>) billMap.get("weightsHundredths");
            Map<String, Integer> weights = new LinkedHashMap<>();
            if (rawWeights != null) {
                for (Map.Entry<String, Object> entry : rawWeights.entrySet()) {
                    weights.put(entry.getKey(), ((Number) entry.getValue()).intValue());
                }
            }
            NewBill newBill = new NewBill(desc, total, weights, date);
            return billApiController.createBill(access, newBill);
        }));

        server.createContext("/api/bill/change-date", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            long billId = ((Number) body.get("billId")).longValue();
            String dateStr = (String) body.get("newDate");
            Result<LocalDate> parsedDate = InputValidator.parseOptionalDate(dateStr);
            if (!parsedDate.isSuccess()) return Result.fail(parsedDate.getFailureReason());
            LocalDate newDate = parsedDate.getData();
            return billApiController.changeSuggestedDate(access, billId, newDate);
        }));

        server.createContext("/api/bill/confirm-payment", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            long billId = ((Number) body.get("billId")).longValue();
            Object memberOrder = body.get("memberOrder");
            if (memberOrder instanceof Number number) {
                return billApiController.confirmPayment(access, billId, number.intValue());
            }
            return billApiController.confirmPayment(access, billId, (String) body.get("memberName"));
        }));

        server.createContext("/api/bill/cancel", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            long billId = ((Number) body.get("billId")).longValue();
            return billApiController.cancelBill(access, billId);
        }));

        server.createContext("/api/bill/cancel-all", exchange -> handlePost(exchange, body -> {
            GroupAccess access = extractGroupAccess(body);
            return billApiController.cancelAllBills(access);
        }));

        // Static files handler for web UI
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("IOU server listening on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int getPort() {
        return server != null ? server.getAddress().getPort() : port;
    }

    @FunctionalInterface
    private interface RequestAction {
        Result<?> execute(Map<String, Object> body) throws Exception;
    }

    private void handlePost(HttpExchange exchange, RequestAction action) throws IOException {
        try {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendCors(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            byte[] requestBytes = exchange.getRequestBody().readNBytes(1_048_577);
            if (requestBytes.length > 1_048_576) {
                sendJson(exchange, 413, Result.fail("Request body is too large"));
                return;
            }
            String bodyStr = new String(requestBytes, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) SimpleJson.parse(bodyStr);
            if (body == null) body = Collections.emptyMap();

            Result<?> result = action.execute(body);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            // GEN-13: The server shall not terminate with an unhandled exception as a result of any request it receives
            Result<?> errResult = Result.fail("Server error: " + e.getMessage());
            sendJson(exchange, 200, errResult);
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendCors(exchange);
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            if (postgresDatabase != null) {
                postgresDatabase.healthCheck();
            }
            sendJson(exchange, 200, Result.ok(Map.of("status", "ok")));
        } catch (StorageException e) {
            sendJson(exchange, 503, Result.fail("Database is unavailable"));
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = SimpleJson.toJson(value).getBytes(StandardCharsets.UTF_8);
        sendCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static int configuredPort() {
        String value = System.getenv("PORT");
        if (value == null || value.isBlank()) return 8080;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 8080;
        }
    }

    private void sendCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private Credentials extractCredentials(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> credsMap = (Map<String, Object>) body.get("credentials");
        if (credsMap == null) return null;
        String u = (String) credsMap.get("username");
        String t = (String) credsMap.get("loginToken");
        return new Credentials(u, t);
    }

    private GroupAccess extractGroupAccess(Map<String, Object> body) {
        Credentials creds = extractCredentials(body);
        long groupId = body.containsKey("groupId") ? ((Number) body.get("groupId")).longValue() : 0L;
        String groupCode = (String) body.get("groupCode");
        return new GroupAccess(creds, groupId, groupCode);
    }

    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestedPath = exchange.getRequestURI().getPath();
            if (requestedPath == null || requestedPath.equals("/") || requestedPath.isEmpty()) {
                requestedPath = "/index.html";
            }
            Path relativePath = Path.of(requestedPath.substring(1)).normalize();
            if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String resourcePath = "/web/" + relativePath.toString().replace('\\', '/');
            InputStream is = getClass().getResourceAsStream(resourcePath);
            if (is == null) {
                Path webRoot = Path.of("src/main/resources/web").toAbsolutePath().normalize();
                Path file = webRoot.resolve(relativePath).normalize();
                if (file.startsWith(webRoot) && Files.isRegularFile(file)) {
                    is = Files.newInputStream(file);
                }
            }

            if (is == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String fileName = relativePath.getFileName().toString().toLowerCase(Locale.ROOT);
            String contentType = "application/octet-stream";
            if (fileName.endsWith(".html")) contentType = "text/html; charset=UTF-8";
            else if (fileName.endsWith(".css")) contentType = "text/css; charset=UTF-8";
            else if (fileName.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
            else if (fileName.endsWith(".png")) contentType = "image/png";
            else if (fileName.endsWith(".svg")) contentType = "image/svg+xml";
            else if (fileName.endsWith(".ico")) contentType = "image/x-icon";

            byte[] bytes;
            try (InputStream input = is) {
                bytes = input.readAllBytes();
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int port = configuredPort();
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
                System.err.println("Invalid port argument; using " + port);
            }
        }
        ServerMain server = new ServerMain(port);
        server.start();
    }
}
