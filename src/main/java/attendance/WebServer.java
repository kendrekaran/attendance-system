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
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the attendance page and a small JSON API with the JDK's built-in HTTP server.
 * <p>
 * How it works in one paragraph: the browser opens http://localhost:8080 and gets index.html.
 * After that, the JavaScript in that page talks to this class by sending small requests
 * (for example "give me the students" or "save this attendance"). Each request has a
 * method (GET to read, POST to change something) and a path (like /api/students).
 * This class looks at the method and path, does the work with {@link Database},
 * and sends back an answer written as JSON text.
 * <p>
 * Routes:
 * <ul>
 *     <li>{@code GET /} the page</li>
 *     <li>{@code POST /api/login} log in (username, password), {@code POST /api/logout} log out</li>
 *     <li>{@code GET /api/me} who is logged in</li>
 *     <li>{@code GET /api/students?division=All&date=yyyy-MM-dd} students for the filter</li>
 *     <li>{@code POST /api/students} add a student (name, roll, division)</li>
 *     <li>{@code POST /api/students/update} edit a student (id, name, roll, division)</li>
 *     <li>{@code POST /api/students/delete} delete a student and their marks (id)</li>
 *     <li>{@code POST /api/attendance} mark a day (studentId, date, status)</li>
 *     <li>{@code POST /api/attendance/all} mark a whole class (date, marks = id:STATUS,id:STATUS)</li>
 *     <li>{@code GET /api/history?id=} every marked day for one student</li>
 *     <li>{@code GET /export?division=All} the filtered rows as an .xlsx download</li>
 * </ul>
 * Everything except the page and the login needs a logged-in teacher. After login the browser
 * keeps a random token in a cookie called {@code session}, and the server remembers which user it belongs to.
 * <p>
 * Requests run one at a time, so the single JDBC connection is never shared between threads.
 * <p>
 * HTTP status codes used in this file (the number the server sends back with every answer):
 * <ul>
 *     <li>200 OK: it worked.</li>
 *     <li>400 Bad Request: the form was wrong, for example a blank name or a bad date.</li>
 *     <li>401 Unauthorized: not logged in, or wrong password.</li>
 *     <li>403 Forbidden: the request did not come from our page.</li>
 *     <li>404 Not Found: the page or the student does not exist.</li>
 *     <li>405 Method Not Allowed: right path, wrong method (for example GET where POST is needed).</li>
 *     <li>409 Conflict: the roll number is already used by another student.</li>
 *     <li>413 Payload Too Large: the request body was bigger than we allow.</li>
 *     <li>500 Internal Server Error: a bug in our Java code.</li>
 *     <li>503 Service Unavailable: MySQL is not running or cannot be reached.</li>
 * </ul>
 */
public class WebServer {
    /**
     * POST requests must send this header, so another website cannot submit a form to this one.
     * Our own JavaScript always adds "X-Requested-With: attendance" when it sends a POST.
     * A normal HTML form on some other website cannot add this header, so its requests are refused.
     */
    public static final String REQUEST_HEADER = "X-Requested-With";
    public static final String REQUEST_HEADER_VALUE = "attendance";

    // The biggest form body we accept: 64 KB. Real forms here are tiny, so this stops someone
    // from sending a huge request and filling the server's memory.
    private static final int MAX_BODY_BYTES = 64 * 1024;
    // The official "content type" name for an Excel .xlsx file. The browser uses it to know what it is downloading.
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    // Name of the cookie that holds the login token.
    private static final String SESSION_COOKIE = "session";

    private final Database database;
    private final HookManager hooks;
    /** Logged-in users: session token -> username. Restarting the server logs everyone out. */
    private final Map<String, String> sessions = new HashMap<>();
    // SecureRandom makes random numbers that cannot be guessed, unlike java.util.Random.
    // We use it for login tokens, so nobody can guess another teacher's token.
    private final SecureRandom random = new SecureRandom();
    private HttpServer server;

    /** Keeps the database and the hooks so every request handler can use them. Called once from Main. */
    public WebServer(Database database, HookManager hooks) {
        this.database = database;
        this.hooks = hooks;
    }

    /** Starts listening for browser requests on the given host and port. Called once from Main. */
    public void start(String host, int port) throws IOException {
        // HttpServer is a small web server that comes with the JDK, so we need no extra library.
        // The 0 means "use the default number of waiting connections".
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        // createContext("/") means: send EVERY request, whatever its path, to our handle() method.
        server.createContext("/", this::handle);
        // null executor means requests are handled one at a time on one thread.
        // That keeps things simple and safe, because we only have one database connection.
        server.setExecutor(null);
        server.start();
    }

    /** The port the server is really using. Tests start on port 0 (any free port) and read it here. */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /** Stops the web server and closes the database. Called when the program shuts down (Ctrl+C). */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        database.close();
    }

    /**
     * The first method every request goes through. It calls route() and makes sure that,
     * even if our code crashes, the browser still gets an answer (500) instead of waiting forever.
     */
    private void handle(HttpExchange exchange) throws IOException {
        // HttpExchange is one request plus its answer: we read the request from it and write the reply to it.
        try {
            route(exchange);
        } catch (RuntimeException ex) {
            // A bug in our code: print it in the terminal for us, and send a 500 error to the page.
            ex.printStackTrace();
            sendJson(exchange, 500, "{\"error\":" + json("Something went wrong on the server. " + Database.describe(ex)) + "}");
        } finally {
            // Always close the exchange, or the browser keeps waiting for more data.
            exchange.close();
        }
    }

    /**
     * Looks at the method (GET or POST) and the path (like /api/students) and calls the right method.
     * Think of it as the "menu" of the server.
     */
    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // Safety check: a POST (which changes data) must carry our special header.
        // If it is missing, the request came from somewhere else, so we refuse it with 403 Forbidden.
        if (method.equals("POST") && !REQUEST_HEADER_VALUE.equals(exchange.getRequestHeaders().getFirst(REQUEST_HEADER))) {
            sendError(exchange, 403, "This request was not sent by the attendance page.");
            return;
        }

        // The page itself (HTML, CSS and JavaScript in one file). Anyone may load it,
        // because it shows the login form first.
        if (path.equals("/") || path.equals("/index.html")) {
            requireGet(exchange, () -> sendResource(exchange, "web/index.html", "text/html; charset=utf-8"));
            return;
        }
        // Login and logout must work without being logged in, so they come before the login check.
        if (path.equals("/api/login") && method.equals("POST")) {
            login(exchange);
            return;
        }
        if (path.equals("/api/logout") && method.equals("POST")) {
            logout(exchange);
            return;
        }

        // Everything below needs a logged-in teacher.
        // 401 Unauthorized tells the page "you are not logged in", and the page then shows the login form.
        String username = loggedInUser(exchange);
        if (username == null && (path.startsWith("/api/") || path.equals("/export"))) {
            sendJson(exchange, 401, "{\"error\":\"Please log in first.\",\"loggedIn\":false}");
            return;
        }

        // 200 OK means "it worked". The page calls /api/me when it opens, to check if we are still logged in.
        if (path.equals("/api/me") && method.equals("GET")) {
            sendJson(exchange, 200, "{\"loggedIn\":true,\"username\":" + json(username) + "}");
        } else if (path.equals("/api/history") && method.equals("GET")) {
            studentHistory(exchange);
        } else if (path.equals("/api/students") && method.equals("GET")) {
            listStudents(exchange);
        } else if (path.equals("/api/students") && method.equals("POST")) {
            addStudent(exchange);
        } else if (path.equals("/api/students/update") && method.equals("POST")) {
            updateStudent(exchange);
        } else if (path.equals("/api/students/delete") && method.equals("POST")) {
            deleteStudent(exchange);
        } else if (path.equals("/api/attendance") && method.equals("POST")) {
            markAttendance(exchange);
        } else if (path.equals("/api/attendance/all") && method.equals("POST")) {
            markWholeClass(exchange);
        } else if (path.equals("/export")) {
            requireGet(exchange, () -> export(exchange));
        } else if (path.startsWith("/api/students") || path.startsWith("/api/attendance")
                || path.equals("/api/login") || path.equals("/api/logout") || path.equals("/api/me")
                || path.equals("/api/history")) {
            // 405 Method Not Allowed: the path exists, but not with this method (for example GET /api/login).
            sendError(exchange, 405, "Method not allowed.");
        } else {
            // 404 Not Found: we have nothing at this path.
            sendError(exchange, 404, "Not found.");
        }
    }

    /**
     * Checks the username and password and, if they are right, logs the teacher in.
     * Called when the teacher clicks Login on the login page.
     */
    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");
        // 400 Bad Request means "the form you sent is not right", here because a box was left empty.
        if (username.isEmpty() || password.isEmpty()) {
            sendError(exchange, 400, "Enter your username and password.");
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }
        boolean correct;
        try {
            // Database hashes the typed password and compares it with the hash saved in the users table.
            correct = database.checkLogin(username, password);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        if (!correct) {
            sendError(exchange, 401, "Wrong username or password.");
            return;
        }
        String token = startSession(username);
        // Set-Cookie tells the browser to save the token and send it back with every later request.
        // HttpOnly: JavaScript in the page cannot read the cookie, so a script cannot steal it.
        // SameSite=Strict: the browser does not send the cookie when another website makes the request.
        // Path=/: the cookie is sent for every path on this server.
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        sendJson(exchange, 200, "{\"ok\":true,\"username\":" + json(username) + "}");
    }

    /** Logs the teacher out. Called when the teacher clicks Logout in the top bar. */
    private void logout(HttpExchange exchange) throws IOException {
        String token = sessionToken(exchange);
        if (token != null) {
            // Forget the token, so it no longer works even if someone still has it.
            sessions.remove(token);
        }
        // Max-Age=0 tells the browser to delete the cookie right away.
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    /** Makes a random token for the user and remembers it. Tests call this to skip the login form. */
    String startSession(String username) {
        // 24 random bytes from SecureRandom. That is far too many possibilities for anyone to guess.
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        // Base64 turns the bytes into normal letters and digits, so the token can go in a cookie.
        // The "URL" version avoids + and / characters, and withoutPadding drops the = signs at the end.
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Remember which user this token belongs to. The map lives in memory only.
        sessions.put(token, username);
        return token;
    }

    /** The username for this request's session cookie, or null when nobody is logged in. */
    private String loggedInUser(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token == null) {
            return null;
        }
        // If the token is unknown (made up, or from before a restart) this returns null too.
        return sessions.get(token);
    }

    /**
     * Finds our session token in the request's Cookie header.
     * The browser sends all cookies in one line, like "session=abc123; other=xyz", so we split it on ";".
     */
    private static String sessionToken(HttpExchange exchange) {
        String cookies = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies.split(";")) {
            String pair = cookie.trim();
            if (pair.startsWith(SESSION_COOKIE + "=")) {
                // Everything after "session=" is the token.
                return pair.substring(SESSION_COOKIE.length() + 1);
            }
        }
        return null;
    }

    /**
     * Sends every marked day for one student, oldest first.
     * Called when the teacher clicks View in the History column of the Attendance Report tab.
     */
    private void studentHistory(HttpExchange exchange) throws IOException {
        // For a GET request the values are in the URL after "?", for example /api/history?id=3.
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        int id;
        try {
            id = Integer.parseInt(query.getOrDefault("id", "").trim());
        } catch (NumberFormatException ex) {
            sendError(exchange, 400, "Select a student first.");
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }
        StudentRecord student;
        List<String[]> days;
        try {
            student = findStudent(id);
            if (student == null) {
                sendError(exchange, 404, "That student no longer exists. Refresh the list.");
                return;
            }
            days = database.studentHistory(id);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        // Build the JSON answer by hand with a StringBuilder (no JSON library is needed).
        // The result looks like:
        // {"name":"Rohan","roll":"103","division":"A","percent":"60.0","days":[{"date":"2026-11-02","status":"PRESENT"}, ...]}
        // Text values go through json() so quotes and special characters are written safely.
        StringBuilder out = new StringBuilder();
        out.append("{\"name\":").append(json(student.getName()))
                .append(",\"roll\":").append(json(student.getRollNumber()))
                .append(",\"division\":").append(json(student.getDivision()))
                .append(",\"percent\":").append(json(student.attendancePercent()))
                .append(",\"days\":[");
        for (int i = 0; i < days.size(); i++) {
            // Put a comma between items, but not before the first one.
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"date\":").append(json(days.get(i)[0]))
                    .append(",\"status\":").append(json(days.get(i)[1])).append('}');
        }
        out.append("]}");
        sendJson(exchange, 200, out.toString());
    }

    /**
     * Sends the list of students with their present days, absent days and percentage.
     * If a date is given, each student also gets that day's mark (dayStatus).
     * Called by all three tabs: Students, Mark Attendance (with a date) and Attendance Report.
     */
    private void listStudents(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        String division = divisionFilter(query.get("division"));
        LocalDate day = null;
        String dateText = query.getOrDefault("date", "").trim();
        if (!dateText.isEmpty()) {
            try {
                // LocalDate.parse expects the yyyy-MM-dd format, like 2026-11-10.
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
            // We always load every student, so we can count each division for the dropdown.
            all = database.listStudents("All", day);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }

        // Make a sorted list of the divisions that exist, like [A, B], for the Division dropdown.
        List<String> divisions = new ArrayList<>();
        for (StudentRecord student : all) {
            if (!divisions.contains(student.getDivision())) {
                divisions.add(student.getDivision());
            }
        }
        divisions.sort(null);
        // If the page asked for a division that no longer exists, fall back to All.
        if (!division.equals("All") && !divisions.contains(division)) {
            division = "All";
        }

        // Build the JSON answer by hand. It looks like:
        // {"connected":true,"database":"...","division":"All","date":null,
        //  "divisions":[{"name":"A","count":6},...],"total":12,"students":[{...},{...}]}
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
            // Only send the students of the chosen division.
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

    /** Adds a new student. Called when the teacher clicks Add Student on the Students tab. */
    private void addStudent(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        String name = form.getOrDefault("name", "");
        String roll = form.getOrDefault("roll", "");
        String division = form.getOrDefault("division", "");
        // "Before" hooks run first. They can say no by throwing IllegalArgumentException,
        // for example ValidationHook says "Name cannot be blank." Then we stop and save nothing.
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
            // MySQL refuses a second student with the same roll number (the column is UNIQUE).
            // 409 Conflict means "this clashes with something that already exists".
            if (Database.isDuplicateKey(ex)) {
                sendError(exchange, 409, "That roll number is already used.");
            } else {
                sendDatabaseDown(exchange, ex);
            }
            return;
        }
        // "After" hooks run once the student is saved, for example AuditHook writes a line to hooks.log.
        // The save already happened, so if an after hook complains we only pass it on as a warning.
        String warning = null;
        try {
            hooks.afterAddStudent(name.trim(), roll.trim(), division.trim());
        } catch (IllegalArgumentException ex) {
            warning = ex.getMessage();
        }
        sendJson(exchange, 200, "{\"ok\":true,\"roll\":" + json(roll.trim())
                + ",\"warning\":" + (warning == null ? "null" : json(warning)) + "}");
    }

    /**
     * Changes a student's name, roll number or division.
     * Called when the teacher clicks Edit, changes the form, and clicks Update Student.
     */
    private void updateStudent(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        int id;
        try {
            id = Integer.parseInt(form.getOrDefault("id", "").trim());
        } catch (NumberFormatException ex) {
            sendError(exchange, 400, "Select a student first.");
            return;
        }
        String name = form.getOrDefault("name", "");
        String roll = form.getOrDefault("roll", "");
        String division = form.getOrDefault("division", "");
        // Same idea as adding: before hooks can stop the edit, for example a blank name.
        try {
            hooks.beforeUpdateStudent(name, roll, division);
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }
        try {
            // updateStudent returns false when no student has this id (someone deleted it meanwhile).
            if (!database.updateStudent(id, name, roll, division)) {
                sendError(exchange, 404, "That student no longer exists. Refresh the list.");
                return;
            }
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
            hooks.afterUpdateStudent(name.trim(), roll.trim(), division.trim());
        } catch (IllegalArgumentException ex) {
            warning = ex.getMessage();
        }
        sendJson(exchange, 200, "{\"ok\":true,\"warning\":" + (warning == null ? "null" : json(warning)) + "}");
    }

    /**
     * Deletes a student and all of their attendance.
     * Called when the teacher clicks Delete on the Students tab and confirms.
     */
    private void deleteStudent(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        int id;
        try {
            id = Integer.parseInt(form.getOrDefault("id", "").trim());
        } catch (NumberFormatException ex) {
            sendError(exchange, 400, "Select a student first.");
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }
        // Look the student up first, so the hooks and the log can use their name and roll number.
        StudentRecord student;
        try {
            student = findStudent(id);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        if (student == null) {
            sendError(exchange, 404, "That student no longer exists. Refresh the list.");
            return;
        }
        try {
            hooks.beforeDeleteStudent(student.getName(), student.getRollNumber());
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage());
            return;
        }
        try {
            database.deleteStudent(id);
        } catch (SQLException ex) {
            sendDatabaseDown(exchange, ex);
            return;
        }
        String warning = null;
        try {
            hooks.afterDeleteStudent(student.getName(), student.getRollNumber());
        } catch (IllegalArgumentException ex) {
            warning = ex.getMessage();
        }
        sendJson(exchange, 200, "{\"ok\":true,\"warning\":" + (warning == null ? "null" : json(warning)) + "}");
    }

    /**
     * Saves the whole class for one date. {@code marks} looks like {@code 1:PRESENT,2:ABSENT}.
     * Every mark is checked and every before hook runs first, so a problem saves nothing.
     * <p>
     * Called when the teacher clicks Save Attendance on the Mark Attendance tab.
     * <p>
     * Why check everything first? Imagine 12 students and the 7th mark has a problem.
     * If we saved one by one, the first 6 would be saved and the rest would not, and the
     * class would be half marked. So the work is done in three steps:
     * (1) read and check every mark, (2) run every before hook, (3) only then save them all.
     */
    private void markWholeClass(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (form == null) {
            return;
        }
        LocalDate day;
        try {
            day = LocalDate.parse(form.getOrDefault("date", "").trim());
        } catch (DateTimeParseException ex) {
            sendError(exchange, 400, "Enter the date as yyyy-MM-dd.");
            return;
        }
        String marks = form.getOrDefault("marks", "").trim();
        if (marks.isEmpty()) {
            sendError(exchange, 400, "Mark at least one student.");
            return;
        }
        if (!ensureDatabase(exchange)) {
            return;
        }

        // Step 1: read every "id:STATUS" pair and check it. Nothing is saved yet.
        // The two lists stay in step: students.get(i) has the status statuses.get(i).
        List<StudentRecord> students = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (String pair : marks.split(",")) {
            // "3:ABSENT" becomes ["3", "ABSENT"].
            String[] parts = pair.split(":");
            if (parts.length != 2) {
                sendError(exchange, 400, "Could not read the marks.");
                return;
            }
            int studentId;
            try {
                studentId = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException ex) {
                sendError(exchange, 400, "Could not read the marks.");
                return;
            }
            String status = parts[1].trim();
            if (!status.equals(Database.PRESENT) && !status.equals(Database.ABSENT)) {
                sendError(exchange, 400, "Status must be PRESENT or ABSENT.");
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
                sendError(exchange, 404, "A student in the list no longer exists. Refresh the page.");
                return;
            }
            students.add(student);
            statuses.add(status);
        }

        // Step 2: run the before hooks for every mark. FutureDateHook says no to a date after today.
        // If any hook says no, we stop here and nothing at all is saved.
        try {
            for (int i = 0; i < students.size(); i++) {
                hooks.beforeMarkAttendance(students.get(i).getRollNumber(), day.toString(), statuses.get(i));
            }
        } catch (IllegalArgumentException ex) {
            sendError(exchange, 400, ex.getMessage() + " Nothing was saved.");
            return;
        }

        // Step 3: everything passed, so save each mark and run the after hooks (AuditHook logs each one).
        int present = 0;
        String warning = null;
        for (int i = 0; i < students.size(); i++) {
            try {
                database.markAttendance(students.get(i).getId(), day, statuses.get(i));
            } catch (SQLException ex) {
                sendDatabaseDown(exchange, ex);
                return;
            }
            try {
                hooks.afterMarkAttendance(students.get(i).getRollNumber(), day.toString(), statuses.get(i));
            } catch (IllegalArgumentException ex) {
                warning = ex.getMessage();
            }
            if (statuses.get(i).equals(Database.PRESENT)) {
                present++;
            }
        }
        // The page uses saved, present and absent to show "Attendance saved for 12 students (11 present, 1 absent)."
        sendJson(exchange, 200, "{\"ok\":true,\"saved\":" + students.size()
                + ",\"present\":" + present
                + ",\"absent\":" + (students.size() - present)
                + ",\"warning\":" + (warning == null ? "null" : json(warning)) + "}");
    }

    /**
     * Marks ONE student for one date. The page now saves the whole class with markWholeClass,
     * but this route is kept for single marks and is covered by the tests.
     */
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

    /**
     * Builds the Excel file and sends it as a download.
     * Called when the teacher clicks Export to Excel on the Attendance Report tab.
     */
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

        // Write the workbook into memory (a byte array) instead of a file on disk.
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
        // Content-Type says "this is an Excel file".
        headers.set("Content-Type", XLSX);
        // Content-Disposition: attachment tells the browser to download the file instead of
        // trying to show it, and filename= gives the name it is saved under.
        headers.set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        // no-store: the browser must not keep an old copy. Every export should have fresh numbers.
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
        // Keep only safe characters. A quote or a semicolon in the division name
        // could otherwise break the Content-Disposition header.
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

    /** Finds one student by id, or returns null. Simple: load everyone and look through the list. */
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
        // If MySQL was stopped and started again, connect() makes a new connection,
        // so the website keeps working without restarting the Java program.
        if (database.isConnected() || database.connect()) {
            return true;
        }
        sendDatabaseDown(exchange, null);
        return false;
    }

    /**
     * Tells the page that MySQL cannot be reached.
     * 503 Service Unavailable means "the server is running, but something it needs (MySQL) is not".
     * The page shows the error text in a red box.
     */
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

    /**
     * Adds one student as a JSON object to the answer, like
     * {"id":3,"roll":"103","name":"Rohan","division":"A","present":3,"absent":2,"percent":"60.0","dayStatus":null}.
     * Numbers are written as they are, text goes through json() so it gets quotes and escaping.
     */
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

    /** Counts how many students are in one division. Used for the numbers in the Division dropdown. */
    private static int countIn(List<StudentRecord> students, String division) {
        int count = 0;
        for (StudentRecord student : students) {
            if (division.equals(student.getDivision())) {
                count++;
            }
        }
        return count;
    }

    /** Turns a missing or blank division into "All", and trims spaces from the rest. */
    private static String divisionFilter(String value) {
        if (value == null || value.isBlank()) {
            return "All";
        }
        return value.trim();
    }

    /**
     * A small "piece of work" that may throw IOException. We need our own interface because
     * Java's Runnable is not allowed to throw IOException.
     */
    private interface IoAction {
        void run() throws IOException;
    }

    /** Runs the action only for GET requests. Anything else gets 405 Method Not Allowed. */
    private static void requireGet(HttpExchange exchange, IoAction action) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendError(exchange, 405, "Method not allowed.");
            return;
        }
        action.run();
    }

    /**
     * Sends a file from src/main/resources (here, index.html) to the browser.
     * Called when the browser opens http://localhost:8080/.
     */
    private static void sendResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        byte[] bytes;
        // getResourceAsStream reads the file from the program's classpath, which works
        // both when running from Maven and from a .jar file.
        try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                sendError(exchange, 404, "Not found.");
                return;
            }
            bytes = in.readAllBytes();
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // nosniff: the browser must trust our Content-Type and not guess the file type itself.
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            // Content-Security-Policy (CSP) is a list of rules for the browser about what the page may load.
            // 'self' means "only from this same server". So the page cannot load scripts, styles,
            // fonts or pictures from other websites, and cannot send data to them.
            // 'unsafe-inline' allows the <script> and <style> written inside index.html itself.
            // frame-ancestors 'none' stops other websites from showing our page inside a frame.
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Reads the body of a POST request and turns it into a map of form values.
     * The page sends forms like "name=Asha+Rao&roll=101&division=A".
     * Returns null (after sending an error) when the body is too big.
     */
    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            // Read at most one byte MORE than the limit. If we really get that extra byte,
            // we know the body is too big, without ever reading a huge body into memory.
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            sendError(exchange, 413, "Request is too large.");
            return null;
        }
        return parseForm(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Parses {@code a=1&b=two} from a query string or a form body.
     * <p>
     * Browsers "encode" special characters before sending them: a space becomes + and
     * other characters become %XX codes, for example "&" becomes %26.
     * URLDecoder.decode turns them back into the real text, so "Asha+Rao" becomes "Asha Rao".
     */
    static Map<String, String> parseForm(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return values;
        }
        // Each pair is separated by &, and each key is separated from its value by =.
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

    /** Sends an error as JSON, like {"error":"Name cannot be blank."}. The page shows this text in a red box. */
    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + json(message) + "}");
    }

    /**
     * Sends a JSON answer with a status code. Every answer from the API goes through here.
     * The page's JavaScript reads it with response.json().
     */
    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        // Always ask the browser for fresh data, never an old saved copy.
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // The headers must be sent before the body. The second number is the body length in bytes.
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * A JSON string literal. Angle brackets and ampersands are escaped too.
     * <p>
     * Turns Java text into JSON text with quotes around it. For example the name
     * {@code Ravi "R" Kumar} becomes {@code "Ravi \"R\" Kumar"}. Without this, a quote in a name
     * would end the JSON string early and break the whole answer.
     * <p>
     * Why escape &lt; &gt; and &amp; as < and so on? So a name like "&lt;script&gt;" can never
     * be read as HTML or JavaScript. It stays plain text when the page shows it.
     * Control characters (like a new line) are also turned into codes, because JSON does not allow them raw.
     */
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
                    // Characters below 0x20 are invisible control characters. 2028 and 2029 are
                    // special line separators that break some JavaScript. Write them as \\uXXXX codes.
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
