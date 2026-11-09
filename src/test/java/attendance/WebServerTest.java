package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Starts the real HTTP server on a free port. MySQL is replaced by an in-memory
 * {@link Database} subclass, or by one that is always down.
 */
class WebServerTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private WebServer server;
    private String base;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servesThePageAndFont() throws Exception {
        start(new MemoryDatabase(), new HookManager());
        HttpResponse<String> page = get("/");
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(page.body().contains("Attendance Register"));
        assertTrue(page.body().contains("Export Excel"));

        HttpResponse<byte[]> font = client.send(request("/fonts/public-sans.woff2").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, font.statusCode());
        assertTrue(font.body().length > 1000);

        assertEquals(404, get("/fonts/../db.properties").statusCode());
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void listsStudentsWithPercentDashAndDayStatus() throws Exception {
        MemoryDatabase database = new MemoryDatabase();
        database.add("Asha Rao", "101", "A");
        database.add("Ben Cole", "102", "A");
        database.add("Cara Diaz", "201", "B");
        database.markAttendance(1, LocalDate.of(2026, 11, 3), "PRESENT");
        database.markAttendance(1, LocalDate.of(2026, 11, 4), "PRESENT");
        database.markAttendance(1, LocalDate.of(2026, 11, 5), "ABSENT");
        start(database, new HookManager());

        String all = get("/api/students?division=All&date=2026-11-05").body();
        assertTrue(all.contains("\"connected\":true"));
        assertTrue(all.contains("\"total\":3"));
        assertTrue(all.contains("{\"name\":\"A\",\"count\":2}"));
        assertTrue(all.contains("{\"name\":\"B\",\"count\":1}"));
        assertTrue(all.contains("\"name\":\"Asha Rao\",\"division\":\"A\",\"present\":2,\"absent\":1,\"percent\":\"66.7\",\"dayStatus\":\"ABSENT\""));
        assertTrue(all.contains("\"name\":\"Ben Cole\",\"division\":\"A\",\"present\":0,\"absent\":0,\"percent\":\"\u2014\",\"dayStatus\":null"));

        String divisionB = get("/api/students?division=B").body();
        assertTrue(divisionB.contains("Cara Diaz"));
        assertFalse(divisionB.contains("Asha Rao"));
        assertTrue(divisionB.contains("\"division\":\"B\""));

        String unknown = get("/api/students?division=Z").body();
        assertTrue(unknown.contains("\"division\":\"All\""));

        assertEquals(400, get("/api/students?date=09-11-2026").statusCode());
    }

    @Test
    void addStudentRunsHooksAndValidationBlocksSaving() throws Exception {
        MemoryDatabase database = new MemoryDatabase();
        List<String> calls = new ArrayList<>();
        HookManager hooks = new HookManager();
        hooks.register(new ValidationHook());
        hooks.register(new Hook() {
            @Override
            public void beforeAddStudent(String name, String rollNumber, String division) {
                calls.add("before " + rollNumber);
            }

            @Override
            public void afterAddStudent(String name, String rollNumber, String division) {
                calls.add("after " + rollNumber);
            }
        });
        start(database, hooks);

        HttpResponse<String> blank = post("/api/students", "name=+&roll=301&division=c");
        assertEquals(400, blank.statusCode());
        assertTrue(blank.body().contains("Name cannot be blank."));
        assertEquals(0, database.students.size());
        assertTrue(calls.isEmpty());

        HttpResponse<String> ok = post("/api/students", "name=Gita+Sen&roll=301&division=c");
        assertEquals(200, ok.statusCode());
        assertEquals(1, database.students.size());
        assertEquals(List.of("before 301", "after 301"), calls);

        HttpResponse<String> duplicate = post("/api/students", "name=Hari&roll=301&division=c");
        assertEquals(409, duplicate.statusCode());
        assertTrue(duplicate.body().contains("That roll number is already used."));
    }

    @Test
    void markingTheSameDayAgainUpdatesThatDay() throws Exception {
        MemoryDatabase database = new MemoryDatabase();
        database.add("Asha Rao", "101", "A");
        List<String> calls = new ArrayList<>();
        HookManager hooks = new HookManager();
        hooks.register(new Hook() {
            @Override
            public void beforeMarkAttendance(String rollNumber, String date, String status) {
                calls.add("before " + rollNumber + " " + date + " " + status);
            }
        });
        start(database, hooks);

        assertEquals(200, post("/api/attendance", "studentId=1&date=2026-11-09&status=PRESENT").statusCode());
        assertEquals(200, post("/api/attendance", "studentId=1&date=2026-11-09&status=ABSENT").statusCode());
        assertEquals(1, database.marks.size());
        assertEquals("ABSENT", database.marks.get(0)[2]);
        assertEquals(List.of("before 101 2026-11-09 PRESENT", "before 101 2026-11-09 ABSENT"), calls);

        String list = get("/api/students?date=2026-11-09").body();
        assertTrue(list.contains("\"present\":0,\"absent\":1,\"percent\":\"0.0\",\"dayStatus\":\"ABSENT\""));

        assertEquals(400, post("/api/attendance", "studentId=1&date=2026/11/09&status=PRESENT").statusCode());
        assertEquals(400, post("/api/attendance", "studentId=1&date=2026-11-09&status=LATE").statusCode());
        assertEquals(404, post("/api/attendance", "studentId=99&date=2026-11-09&status=PRESENT").statusCode());
    }

    @Test
    void exportDownloadsOnlyTheFilteredRows() throws Exception {
        MemoryDatabase database = new MemoryDatabase();
        database.add("Asha Rao", "101", "A");
        database.add("Cara Diaz", "201", "B");
        database.markAttendance(2, LocalDate.of(2026, 11, 3), "PRESENT");
        List<String> calls = new ArrayList<>();
        HookManager hooks = new HookManager();
        hooks.register(new Hook() {
            @Override
            public void beforeExport(int rowCount) {
                calls.add("before " + rowCount);
            }

            @Override
            public void afterExport(int rowCount, String filePath) {
                calls.add("after " + rowCount + " " + filePath);
            }
        });
        start(database, hooks);

        HttpResponse<byte[]> response = client.send(request("/export?division=B").build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("attachment; filename=\"attendance-division-B.xlsx\"",
                response.headers().firstValue("Content-Disposition").orElse(""));
        assertEquals(List.of("before 1", "after 1 attendance-division-B.xlsx"), calls);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.body()))) {
            Sheet sheet = workbook.getSheet("Attendance");
            Row header = sheet.getRow(0);
            for (int column = 0; column < ExcelExporter.COLUMNS.length; column++) {
                assertEquals(ExcelExporter.COLUMNS[column], header.getCell(column).getStringCellValue());
            }
            assertEquals(1, sheet.getLastRowNum());
            assertEquals("Cara Diaz", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("100.0", sheet.getRow(1).getCell(5).getStringCellValue());
        }

        start(new MemoryDatabase(), new HookManager());
        HttpResponse<String> empty = get("/export?division=All");
        assertEquals(400, empty.statusCode());
        assertTrue(empty.body().contains("There are no rows to export"));
    }

    @Test
    void mysqlDownIsReportedAndTheServerKeepsAnswering() throws Exception {
        start(new Database("Connection refused"), new HookManager());

        HttpResponse<String> list = get("/api/students");
        assertEquals(503, list.statusCode());
        assertTrue(list.body().contains("\"connected\":false"));
        assertTrue(list.body().contains("Cannot reach MySQL"));
        assertTrue(list.body().contains("Connection refused"));

        HttpResponse<String> add = post("/api/students", "name=Gita&roll=301&division=C");
        assertEquals(503, add.statusCode());
        assertTrue(add.body().contains("Nothing was saved."));

        assertEquals(503, post("/api/attendance", "studentId=1&date=2026-11-09&status=PRESENT").statusCode());
        assertEquals(503, get("/export").statusCode());
        assertEquals(200, get("/").statusCode());
        assertEquals(503, get("/api/students").statusCode());
    }

    @Test
    void blankFormIsRejectedEvenWhenMysqlIsDown() throws Exception {
        HookManager hooks = new HookManager();
        hooks.register(new ValidationHook());
        start(new Database("Connection refused"), hooks);
        HttpResponse<String> blank = post("/api/students", "name=Gita&roll=&division=C");
        assertEquals(400, blank.statusCode());
        assertTrue(blank.body().contains("Roll number cannot be blank."));
    }

    @Test
    void postWithoutThePageHeaderIsRefused() throws Exception {
        MemoryDatabase database = new MemoryDatabase();
        start(database, new HookManager());
        HttpRequest bare = request("/api/students")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=X&roll=1&division=A"))
                .build();
        assertEquals(403, client.send(bare, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, database.students.size());
    }

    @Test
    void jsonEscapesQuotesAndMarkup() {
        assertEquals("\"a\\\"b\\\\c\"", WebServer.json("a\"b\\c"));
        assertEquals("\"\\u003cscript\\u003e\"", WebServer.json("<script>"));
        assertEquals("\"line\\nnext\"", WebServer.json("line\nnext"));
    }

    @Test
    void exportFileNameKeepsOnlySafeCharacters() {
        assertEquals("attendance.xlsx", WebServer.exportFileName("All"));
        assertEquals("attendance-division-A.xlsx", WebServer.exportFileName("A"));
        assertEquals("attendance-division-B2.xlsx", WebServer.exportFileName("B\"2;"));
        assertEquals("attendance.xlsx", WebServer.exportFileName("\"\";"));
    }

    @Test
    void parseFormDecodesPlusAndPercent() {
        assertEquals("Asha Rao", WebServer.parseForm("name=Asha+Rao").get("name"));
        assertEquals("A&B", WebServer.parseForm("division=A%26B").get("division"));
        assertEquals("", WebServer.parseForm("roll=").get("roll"));
    }

    private void start(Database database, HookManager hooks) throws Exception {
        if (server != null) {
            server.stop();
        }
        server = new WebServer(database, hooks);
        server.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.getPort();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String form) throws Exception {
        HttpRequest request = request(path)
                .header(WebServer.REQUEST_HEADER, WebServer.REQUEST_HEADER_VALUE)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Keeps students and marks in lists, like the two MySQL tables. */
    private static final class MemoryDatabase extends Database {
        final List<String[]> students = new ArrayList<>();
        final List<String[]> marks = new ArrayList<>();

        MemoryDatabase() {
            super("in memory");
        }

        void add(String name, String roll, String division) throws SQLException {
            addStudent(name, roll, division);
        }

        @Override
        public boolean connect() {
            return true;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public String location() {
            return "memory";
        }

        @Override
        public void addStudent(String name, String rollNumber, String division) throws SQLException {
            for (String[] student : students) {
                if (student[1].equals(rollNumber.trim())) {
                    throw new SQLException("Duplicate entry", "23000", 1062);
                }
            }
            students.add(new String[] {name.trim(), rollNumber.trim(), division.trim().toUpperCase()});
        }

        @Override
        public List<String> listDivisions() {
            List<String> divisions = new ArrayList<>();
            for (String[] student : students) {
                if (!divisions.contains(student[2])) {
                    divisions.add(student[2]);
                }
            }
            return divisions;
        }

        @Override
        public List<StudentRecord> listStudents(String divisionFilter) {
            return listStudents(divisionFilter, null);
        }

        @Override
        public List<StudentRecord> listStudents(String divisionFilter, LocalDate day) {
            List<StudentRecord> rows = new ArrayList<>();
            for (int i = 0; i < students.size(); i++) {
                String[] student = students.get(i);
                if (!"All".equals(divisionFilter) && !student[2].equals(divisionFilter)) {
                    continue;
                }
                int id = i + 1;
                int present = 0;
                int absent = 0;
                String dayStatus = null;
                for (String[] mark : marks) {
                    if (!mark[0].equals(String.valueOf(id))) {
                        continue;
                    }
                    if (mark[2].equals("PRESENT")) {
                        present++;
                    } else {
                        absent++;
                    }
                    if (day != null && mark[1].equals(day.toString())) {
                        dayStatus = mark[2];
                    }
                }
                rows.add(new StudentRecord(id, student[1], student[0], student[2], present, absent, dayStatus));
            }
            return rows;
        }

        @Override
        public void markAttendance(int studentId, LocalDate date, String status) {
            for (String[] mark : marks) {
                if (mark[0].equals(String.valueOf(studentId)) && mark[1].equals(date.toString())) {
                    mark[2] = status;
                    return;
                }
            }
            marks.add(new String[] {String.valueOf(studentId), date.toString(), status});
        }
    }
}
