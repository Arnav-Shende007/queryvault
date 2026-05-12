import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class QueryVaultServer {
    private static final String CONFIG_FILE = "config.txt";
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String dbUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int port;

    public QueryVaultServer(Properties config) {
        this.dbUrl = required(config, "DB_URL");
        this.dbUser = required(config, "DB_USER");
        this.dbPassword = required(config, "DB_PASSWORD");
        System.out.println("DB_URL = " + this.dbUrl);
        System.out.println("DB_USER = " + this.dbUser);
        System.out.println("DB_PASSWORD = " + this.dbPassword);
        this.port = Integer.parseInt(config.getProperty("PORT", "8080").trim());
    }

    public static void main(String[] args) {
        try {
            Properties config = loadConfig(Path.of(CONFIG_FILE));
            QueryVaultServer app = new QueryVaultServer(config);
            app.verifyDatabaseConnection();
            app.start();
        } catch (Exception e) {
            System.err.println("QueryVault failed to start: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Properties loadConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            throw new IOException("Missing " + configPath.toAbsolutePath());
        }
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return value.trim();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ApiRouter());
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.println("QueryVault API running on http://localhost:" + port);
    }

    private void verifyDatabaseConnection() throws SQLException {
        try (Connection conn = getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            System.out.println("Connected to " + meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    private class ApiRouter implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();

            try {
                if ("GET".equals(method) && "/api/health".equals(path)) {
                    sendJson(exchange, 200, "{\"status\":\"ok\"}");
                } else if ("POST".equals(method) && "/api/query".equals(path)) {
                    handleSqlQuery(exchange);
                } else if ("GET".equals(method) && "/api/tables".equals(path)) {
                    handleTables(exchange);
                } else if ("GET".equals(method) && "/api/stats".equals(path)) {
                    handleStats(exchange);
                } else if ("GET".equals(method) && path.startsWith("/api/tables/")) {
                    handleTableRoute(exchange, path);
                } else {
                    sendError(exchange, 404, "Route not found");
                }
            } catch (SQLException e) {
                sendError(exchange, 400, cleanSqlMessage(e));
            } catch (BadRequestException e) {
                sendError(exchange, 400, e.getMessage());
            } catch (Exception e) {
                e.printStackTrace(System.err);
                sendError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private void handleSqlQuery(HttpExchange exchange) throws IOException, SQLException {
        String body = readBody(exchange.getRequestBody());
        Map<String, String> json = parseFlatJsonObject(body);
        String sql = json.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            throw new BadRequestException("Request body must include a non-empty sql field");
        }

        long start = System.nanoTime();
        QueryResult result = executeQuery(sql);
        long executionMs = (System.nanoTime() - start) / 1_000_000L;

        String response = "{\"data\":" + rowsToJson(result.rows) +
                ",\"rowCount\":" + result.rowCount +
                ",\"executionMs\":" + executionMs + "}";
        sendJson(exchange, 200, response);
    }

    private void handleTables(HttpExchange exchange) throws IOException, SQLException {
        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema='public'
                AND table_type='BASE TABLE'
                ORDER BY table_name
                """;
        List<Map<String, Object>> rows = executeQuery(sql).rows;
        sendJson(exchange, 200, rowsToJson(rows));
    }

    private void handleTableRoute(HttpExchange exchange, String path) throws IOException, SQLException {
        String[] parts = path.split("/");
        if (parts.length != 5) {
            sendError(exchange, 404, "Route not found");
            return;
        }

        String table = decode(parts[3]);
        String action = parts[4];
        requireSafeIdentifier(table, "table");

        if ("columns".equals(action)) {
            handleColumns(exchange, table);
        } else if ("preview".equals(action)) {
            handlePreview(exchange, table);
        } else if ("count".equals(action)) {
            handleCount(exchange, table);
        } else {
            sendError(exchange, 404, "Route not found");
        }
    }

    private void handleColumns(HttpExchange exchange, String table) throws IOException, SQLException {
        String sql = """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema='public' AND table_name=?
                ORDER BY ordinal_position
                """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                sendJson(exchange, 200, rowsToJson(resultSetToJson(rs)));
            }
        }
    }

    private void handlePreview(HttpExchange exchange, String table) throws IOException, SQLException {
        ensureTableExists(table);
        String sql = "SELECT * FROM " + quoteIdentifier(table) + " LIMIT 100";
        QueryResult result = executeQuery(sql);
        String response = "{\"data\":" + rowsToJson(result.rows) +
                ",\"rowCount\":" + result.rowCount + "}";
        sendJson(exchange, 200, response);
    }

    private void handleCount(HttpExchange exchange, String table) throws IOException, SQLException {
        ensureTableExists(table);
        String sql = "SELECT COUNT(*) AS count FROM " + quoteIdentifier(table);
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            long count = 0;
            if (rs.next()) {
                count = rs.getLong("count");
            }
            sendJson(exchange, 200, "{\"count\":" + count + "}");
        }
    }

    private void handleStats(HttpExchange exchange) throws IOException, SQLException {
        long tableCount = queryLong("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                """);
        long totalRows = estimateTotalRows();
        String dbSize = queryString("SELECT pg_size_pretty(pg_database_size(current_database())) AS db_size");
        long indexCount = queryLong("SELECT COUNT(*) FROM pg_indexes WHERE schemaname='public'");

        String response = "{\"tableCount\":" + tableCount +
                ",\"totalRows\":" + totalRows +
                ",\"dbSize\":" + quoteJson(dbSize) +
                ",\"indexCount\":" + indexCount + "}";
        sendJson(exchange, 200, response);
    }

    // Central query helper used by custom SQL and internal dynamic table reads.
    private QueryResult executeQuery(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(60);
            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    List<Map<String, Object>> rows = resultSetToJson(rs);
                    return new QueryResult(rows, rows.size());
                }
            }
            int updated = stmt.getUpdateCount();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("message", "Statement executed successfully");
            row.put("affectedRows", Math.max(updated, 0));
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row);
            return new QueryResult(rows, Math.max(updated, 0));
        }
    }

    // Converts any ResultSet into ordered row maps by reading ResultSetMetaData.
    private List<Map<String, Object>> resultSetToJson(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String label = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                row.put(label, normalizeSqlValue(value));
            }
            rows.add(row);
        }
        return rows;
    }

    private Object normalizeSqlValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(timestamp.toLocalDateTime());
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return "[binary " + bytes.length + " bytes]";
        }
        return value;
    }

    private long estimateTotalRows() throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(n_live_tup), 0) AS total_rows
                FROM pg_stat_user_tables
                WHERE schemaname='public'
                """;
        return queryLong(sql);
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private void ensureTableExists(String table) throws SQLException {
        String sql = """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE' AND table_name=?
                """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new BadRequestException("Unknown public table: " + table);
                }
            }
        }
    }

    private static void requireSafeIdentifier(String identifier, String label) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new BadRequestException("Invalid " + label + " name");
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // Minimal JSON object parser for {"sql":"..."} style requests, including escaped strings.
    private static Map<String, String> parseFlatJsonObject(String body) {
        JsonCursor cursor = new JsonCursor(body == null ? "" : body);
        return cursor.parseObject();
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type,Authorization");
        headers.set("Access-Control-Max-Age", "86400");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String safeMessage = message == null || message.isBlank() ? "Request failed" : message;
        sendJson(exchange, status, "{\"error\":" + quoteJson(safeMessage) + "}");
    }

    private static String cleanSqlMessage(SQLException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "SQL error";
        }
        int detailIndex = message.indexOf("\n  Detail:");
        if (detailIndex >= 0) {
            message = message.substring(0, detailIndex);
        }
        return message;
    }

    private static String rowsToJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mapToJson(rows.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (index++ > 0) {
                sb.append(',');
            }
            sb.append(quoteJson(entry.getKey())).append(':').append(valueToJson(entry.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? value.toString() : quoteJson(value.toString());
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return quoteJson(String.valueOf(value));
    }

    private static String quoteJson(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private record QueryResult(List<Map<String, Object>> rows, int rowCount) {
    }

    private static class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }

    private static class JsonCursor {
        private final String source;
        private int pos;

        JsonCursor(String source) {
            this.source = source.trim();
        }

        Map<String, String> parseObject() {
            Map<String, String> result = new LinkedHashMap<>();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return result;
            }
            while (pos < source.length()) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = parseValueAsString();
                result.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                    skipWhitespace();
                    continue;
                }
                expect('}');
                skipWhitespace();
                if (pos != source.length()) {
                    throw new BadRequestException("Malformed JSON request body");
                }
                return result;
            }
            throw new BadRequestException("Malformed JSON request body");
        }

        private String parseValueAsString() {
            if (peek('"')) {
                return parseString();
            }
            int start = pos;
            while (pos < source.length() && ",}".indexOf(source.charAt(pos)) == -1) {
                pos++;
            }
            String raw = source.substring(start, pos).trim();
            if (raw.isEmpty()) {
                throw new BadRequestException("Malformed JSON request body");
            }
            return raw;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < source.length()) {
                char c = source.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= source.length()) {
                        throw new BadRequestException("Malformed JSON escape sequence");
                    }
                    char escaped = source.charAt(pos++);
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(parseUnicode());
                        default -> throw new BadRequestException("Malformed JSON escape sequence");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new BadRequestException("Unterminated JSON string");
        }

        private char parseUnicode() {
            if (pos + 4 > source.length()) {
                throw new BadRequestException("Malformed JSON unicode escape");
            }
            String hex = source.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new BadRequestException("Malformed JSON unicode escape");
            }
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char expected) {
            return pos < source.length() && source.charAt(pos) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw new BadRequestException("Malformed JSON request body");
            }
            pos++;
        }
    }
}
