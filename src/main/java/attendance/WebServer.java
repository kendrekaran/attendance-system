package attendance;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the attendance page and a small JSON API with the JDK's built-in HTTP server.
 * <p>
 * Routes:
 * <ul>
 *     <li>{@code GET /} the page</li>
 *     <li>{@code GET /api/students?division=All&date=yyyy-MM-dd} students for the filter</li>
 *     <li>{@code POST /api/students} add a student (name, roll, division)</li>
 *     <li>{@code POST /api/attendance} mark a day (studentId, date, status)</li>
 *     <li>{@code GET /export?division=All} the filtered rows as an .xlsx download</li>
 * </ul>
 * Requests run one at a time, so the single JDBC connection is never shared between threads.
 */
public class WebServer {
    /** POST requests must send this header, so another website cannot submit a form to this one. */
    public static final String REQUEST_HEADER = "X-Requested-With";
    public static final String REQUEST_HEADER_VALUE = "attendance";

    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final Database database;
    private final HookManager hooks;
    private HttpServer server;

    public WebServer(Database database, HookManager hooks) {
        this.database = database;
        this.hooks = hooks;
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        database.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException ex) {
            ex.printStackTrace();
            sendJson(exchange, 500, "{\"error\":" + json("Something went wrong on the server. " + Database.describe(ex)) + "}");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (method.equals("POST") && !REQUEST_HEADER_VALUE.equals(exchange.getRequestHeaders().getFirst(REQUEST_HEADER))) {
            sendError(exchange, 403, "This request was not sent by the attendance page.");
            return;
        }

        if (path.equals("/") || path.equals("/index.html")) {
            requireGet(exchange, () -> sendResource(exchange, "web/index.html", "text/html; charset=utf-8"));
        } else if (path.equals("/fonts/public-sans.woff2")) {
            requireGet(exchange, () -> sendResource(exchange, "web/fonts/public-sans.woff2", "font/woff2"));
        } else if (path.equals("/api/students") && method.equals("GET")) {
            listStudents(exchange);
        } else if (path.equals("/api/students") && method.equals("POST")) {
            addStudent(exchange);
        } else if (path.equals("/api/attendance") && method.equals("POST")) {
            markAttendance(exchange);
        } else if (path.equals("/export")) {
            requireGet(exchange, () -> export(exchange));
        } else if (path.equals("/api/students") || path.equals("/api/attendance")) {
            sendError(exchange, 405, "Method not allowed.");
        } else {
            sendError(exchange, 404, "Not found.");
        }
    }

    private void listStudents(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        String division = divisionFilter(query.get("division"));
        LocalDate day = null;
        String dateText = query.getOrDefault("date", "").trim();
        if (!dateText.isEmpty()) {
            try {
                day = LocalDate.parse(dateText);
            } catch (DateTimeParseException ex) {
                sendError(exchange, 400, "Enter the date as yyyy-MM-dd.");
                return;
            }
        }
        if (!ensureDatabase(exchange)) {
            return;
        }
        List<StudentRecord> all;
        try {
            all = database.listStudents("All", day);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }

        List<String> divisions = new ArrayList<>();
        for (StudentRecord student : all) {
            if (!divisions.contains(student.getDivision())) {
                divisions.add(student.getDivision());
            }
        }
        divisions.sort(null);
        if (!division.equals("All") && !divisions.contains(division)) {
            division = "All";
        }

        StringBuilder out = new StringBuilder();
        out.append("{\"connected\":true");
        out.append(",\"database\":").append(json(database.location()));
        out.append(",\"division\":").append(json(division));
        out.append(",\"date\":").append(day == null ? "null" : json(day.toString()));
        out.append(",\"divisions\":[");
        for (int i = 0; i < divisions.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String name = divisions.get(i);
            out.append("{\"name\":").append(json(name))
                    .append(",\"count\":").append(countIn(all, name)).append('}');
        }
        out.append("],\"total\":").append(all.size());
        out.append(",\"students\":[");
        boolean first = true;
        for (StudentRecord student : all) {
            if (!division.equals("All") && !division.equals(student.getDivision())) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            appendStudent(out, student);
        }
        out.append("]}");
        sendJson(exchange, 200, out.toString());
    }

    private void addStudent(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        String name = form.getOrDefault("name", "");
        String roll = form.getOrDefault("roll", "");
        String division = form.getOrDefault("division", "");
        try {
            hooks.beforeAddStudent(name, roll, division);
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }
        try {
            database.addStudent(name, roll, division);
        } catch (SQLException ex) {
            if (Database.isDuplicateKey(ex)) {
                sendError(exchange, 409, "That roll number is already used.");
            } else {
                sendDatabaseDown(exchange, ex);
            }
            return;
        }
        String warning = null;
        try {
            hooks.afterAddStudent(name.trim(), roll.trim(), division.trim());
        } catch (IllegalArgumentException ex) {
            warning = ex.getMessage();
        }
        sendJson(exchange, 200, "{\"ok\":true,\"roll\":" + json(roll.trim())
                + ",\"warning\":" + (warning == null ? "null" : json(warning)) + "}");
    }

    private void markAttendance(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        int studentId;
        try {
            studentId = Integer.parseInt(form.getOrDefault("studentId", "").trim());
        } catch (NumberFormatException ex) {
            sendError(exchange, 400, "Select a student first.");
            return;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(form.getOrDefault("date", "").trim());
        } catch (DateTimeParseException ex) {
            sendError(exchange, 400, "Enter the date as yyyy-MM-dd.");
            return;
        }
        String status = form.getOrDefault("status", "").trim();
        if (!status.equals(Database.PRESENT) && !status.equals(Database.ABSENT)) {
            sendError(exchange, 400, "Status must be PRESENT or ABSENT.");
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }

        StudentRecord student;
        try {
            student = findStudent(studentId);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        if (student == null) {
            sendError(exchange, 404, "That student no longer exists. Refresh the list.");
            return;
        }

        try {
            hooks.beforeMarkAttendance(student.getRollNumber(), day.toString(), status);
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
            return;
        }
        try {
            database.markAttendance(studentId, day, status);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        String warning = null;
        try {
            hooks.afterMarkAttendance(student.getRollNumber(), day.toString(), status);
        } catch (IllegalArgumentException ex) {
            warning = ex.getMessage();
        }
        sendJson(exchange, 200, "{\"ok\":true,\"warning\":" + (warning == null ? "null" : json(warning)) + "}");
    }

    private void export(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        String division = divisionFilter(query.get("division"));
        if (!ensureDatabase(exchange)) {
            return;
        }
        List<StudentRecord> rows;
        try {
            rows = database.listStudents(division);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        if (rows.isEmpty()) {
            sendError(exchange, 400, "There are no rows to export for this filter.");
            return;
        }
        try {
            hooks.beforeExport(rows.size());
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
            return;
        }

        ByteArrayOutputStream workbook = new ByteArrayOutputStream();
        ExcelExporter.write(workbook, rows);
        String fileName = exportFileName(division);
        try {
            hooks.afterExport(rows.size(), fileName);
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
            return;
        }

        byte[] bytes = workbook.toByteArray();
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", XLSX);
        headers.set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Download name for the export. Only letters, digits, and hyphens come from the division. */
    static String exportFileName(String division) {
        if (division == null || division.equals("All")) {
            return "attendance.xlsx";
        }
        StringBuilder safe = new StringBuilder();
        for (char c : division.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                safe.append(c);
            }
        }
        if (safe.length() == 0) {
            return "attendance.xlsx";
        }
        return "attendance-division-" + safe + ".xlsx";
    }

    private StudentRecord findStudent(int studentId) throws SQLException {
        for (StudentRecord student : database.listStudents("All")) {
            if (student.getId() == studentId) {
                return student;
            }
        }
        return null;
    }

    /** Connects again when needed. Sends the "MySQL is down" reply and returns false when it cannot. */
    private boolean ensureDatabase(HttpExchange exchange) throws IOException {
        if (database.isConnected() || database.connect()) {
            return true;
        }
        sendDatabaseDown(exchange, null);
        return false;
    }

    private void sendDatabaseDown(HttpExchange exchange, SQLException ex) throws IOException {
        String detail = ex == null ? database.getLastError() : Database.describe(ex);
        String error = "Cannot reach MySQL at " + database.location() + ".";
        if (exchange.getRequestMethod().equals("POST")) {
            error += " Nothing was saved.";
        }
        String body = "{\"connected\":false"
                + ",\"database\":" + json(database.location())
                + ",\"error\":" + json(error)
                + ",\"detail\":" + json(detail) + "}";
        sendJson(exchange, 503, body);
    }

    private static void appendStudent(StringBuilder out, StudentRecord student) {
        out.append("{\"id\":").append(student.getId())
                .append(",\"roll\":").append(json(student.getRollNumber()))
                .append(",\"name\":").append(json(student.getName()))
                .append(",\"division\":").append(json(student.getDivision()))
                .append(",\"present\":").append(student.getPresentDays())
                .append(",\"absent\":").append(student.getAbsentDays())
                .append(",\"percent\":").append(json(student.attendancePercent()))
                .append(",\"dayStatus\":")
                .append(student.getDayStatus() == null ? "null" : json(student.getDayStatus()))
                .append('}');
    }

    private static int countIn(List<StudentRecord> students, String division) {
        int count = 0;
        for (StudentRecord student : students) {
            if (division.equals(student.getDivision())) {
                count++;
            }
        }
        return count;
    }

    private static String divisionFilter(String value) {
        if (value == null || value.isBlank()) {
            return "All";
        }
        return value.trim();
    }

    private interface IoAction {
        void run() throws IOException;
    }

    private static void requireGet(HttpExchange exchange, IoAction action) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        action.run();
    }

    private static void sendResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        byte[] bytes;
        try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                sendError(exchange, 404, "Not found.");
                return;
            }
            bytes = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            sendError(exchange, 413, "Request is too large.");
            return null;
        }
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    /** Parses {@code a=1&b=two} from a query string or a form body. */
    static Map<String, String> parseForm(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return values;
        }
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ex) {
                // Skip a pair with broken % escapes.
            }
        }
        return values;
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + json(message) + "}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** A JSON string literal. Angle brackets and ampersands are escaped too. */
    static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
