package attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * JDBC access to attendance_db. Every query uses a {@link PreparedStatement}.
 * Text from the form is bound with setters. It is not pasted into the SQL.
 * <p>
 * How to read this class: every public method is one database job (add a student,
 * list students, mark attendance, check a login...). WebServer calls these methods.
 * <p>
 * JDBC basics used everywhere below:
 * <ul>
 *     <li>A {@link Connection} is the open line to MySQL.</li>
 *     <li>A {@link PreparedStatement} is an SQL command with {@code ?} placeholders.
 *         {@code setString(1, ...)} fills the first {@code ?}, {@code setInt(2, ...)} the second, and so on.
 *         Because the values are sent separately from the SQL text, a name like {@code ' OR 1=1 --}
 *         is just treated as text. This is what protects the app from SQL injection.</li>
 *     <li>{@code executeUpdate()} runs INSERT, UPDATE or DELETE and returns how many rows changed.
 *         {@code executeQuery()} runs SELECT and returns a {@link ResultSet}.</li>
 *     <li>A {@link ResultSet} is read one row at a time with {@code while (rows.next())}.</li>
 *     <li>{@code try (...) { }} is "try-with-resources": Java closes the statement and the
 *         result set by itself when the block ends, even if an error happens.</li>
 * </ul>
 */
public class Database {
    // The two values the status column can have. Using constants avoids typing mistakes.
    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";

    /*
     * The main report query. For every student it counts present days and absent days.
     *
     * - LEFT JOIN keeps students who have no attendance rows yet (their counts become 0).
     * - SUM(CASE WHEN ... THEN 1 ELSE 0 END) counts only the rows that match.
     * - COALESCE(..., 0) turns NULL into 0.
     * - day_status is the student's mark on one chosen date (used by the Mark Attendance tab).
     * - (? = 1 OR s.division = ?) lets one query handle both "All" and a single division.
     *
     * Parameters: 1 = the day to report as day_status (may be NULL),
     * 2 = 1 for every division or 0 for one, 3 = that division.
     */
    private static final String LIST_STUDENTS = """
            SELECT s.id, s.roll_number, s.name, s.division,
                   COALESCE(SUM(CASE WHEN a.status = 'PRESENT' THEN 1 ELSE 0 END), 0) AS present_days,
                   COALESCE(SUM(CASE WHEN a.status = 'ABSENT' THEN 1 ELSE 0 END), 0) AS absent_days,
                   MAX(CASE WHEN a.attendance_date = ? THEN a.status END) AS day_status
            FROM students s
            LEFT JOIN attendance a ON a.student_id = s.id
            WHERE (? = 1 OR s.division = ?)
            GROUP BY s.id, s.roll_number, s.name, s.division
            ORDER BY s.roll_number, s.name
            """;

    private final DbConfig config;    // host, port, user... read from db.properties
    private Connection connection;    // null until connect() works
    private String lastError;         // the last problem, shown on the page when MySQL is down

    public Database(DbConfig config) {
        this.config = config;
        this.lastError = "MySQL is not connected.";
    }

    /** Used when db.properties could not be read. connect() will fail with this reason. */
    public Database(String unavailableReason) {
        this.config = null;
        this.lastError = unavailableReason;
    }

    public String getLastError() {
        if (lastError == null || lastError.isBlank()) {
            return "MySQL is not connected.";
        }
        return lastError;
    }

    /** Text like "localhost:3306/attendance_db", used in messages. */
    public String location() {
        if (config == null) {
            return "the database in db.properties";
        }
        return config.location();
    }

    /**
     * Opens the connection to MySQL. Returns true if it worked.
     * It never throws: on failure it saves the reason in lastError so the page can show it.
     */
    public boolean connect() {
        close();
        if (config == null) {
            return false;
        }
        try {
            // DriverManager finds the MySQL driver (mysql-connector-j in pom.xml) from the URL.
            connection = DriverManager.getConnection(
                    config.jdbcUrl(), config.getUser(), config.getPassword());
            lastError = "";
            return true;
        } catch (SQLException ex) {
            connection = null;
            lastError = describe(ex);
            return false;
        }
    }

    /** True if the connection is open and MySQL still answers (checked with a 2 second timeout). */
    public boolean isConnected() {
        if (connection == null) {
            return false;
        }
        try {
            if (connection.isValid(2)) {
                return true;
            }
            lastError = "The MySQL connection is closed.";
        } catch (SQLException ex) {
            lastError = describe(ex);
        }
        return false;
    }

    /** Closes the connection. Safe to call even when nothing is open. */
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            lastError = describe(ex);
        } finally {
            // finally always runs, so the field is cleared even if close() failed.
            connection = null;
        }
    }

    /** Students tab, Add Student button. Throws SQLException (error 1062) if the roll number already exists. */
    public void addStudent(String name, String rollNumber, String division) throws SQLException {
        String sql = "INSERT INTO students (name, roll_number, division) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, safeTrim(name));
            statement.setString(2, safeTrim(rollNumber));
            statement.setString(3, safeTrim(division));
            statement.executeUpdate();
        }
    }

    /**
     * Login page. Returns true when the username exists and the password is right.
     * The users table keeps a salt and SHA-256(salt + password), so the password itself is never stored.
     * We hash what the teacher typed the same way and compare the two hashes.
     */
    public boolean checkLogin(String username, String password) throws SQLException {
        String sql = "SELECT salt, password_hash FROM users WHERE username = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, safeTrim(username));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;    // no user with that name
                }
                String expected = rows.getString("password_hash");
                String actual = sha256(rows.getString("salt") + (password == null ? "" : password));
                // MessageDigest.isEqual compares every byte, so the time taken
                // does not give away how much of the hash matched.
                return MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** SHA-256 of the text as 64 lowercase hex characters, the same as MySQL's SHA2(text, 256). */
    public static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);    // bytes -> text like "ba7816bf..."
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing from this Java install.", ex);
        }
    }

    /** Report tab, View button. Every marked day for one student, oldest first. Each item is {date, status}. */
    public List<String[]> studentHistory(int studentId) throws SQLException {
        String sql = "SELECT attendance_date, status FROM attendance WHERE student_id = ? ORDER BY attendance_date";
        List<String[]> days = new ArrayList<>();
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setInt(1, studentId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    days.add(new String[] {rows.getString("attendance_date"), rows.getString("status")});
                }
            }
        }
        return days;
    }

    /**
     * Students tab, Update Student button. Changes a student's name, roll number, and division.
     * Returns false if the id is not found (executeUpdate returned 0 rows).
     */
    public boolean updateStudent(int id, String name, String rollNumber, String division) throws SQLException {
        String sql = "UPDATE students SET name = ?, roll_number = ?, division = ? WHERE id = ?";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, safeTrim(name));
            statement.setString(2, safeTrim(rollNumber));
            statement.setString(3, safeTrim(division));
            statement.setInt(4, id);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Students tab, Delete button. Deletes a student. Their attendance rows are deleted first,
     * because the attendance table points at the student (a foreign key).
     */
    public void deleteStudent(int id) throws SQLException {
        try (PreparedStatement marks = connection().prepareStatement("DELETE FROM attendance WHERE student_id = ?")) {
            marks.setInt(1, id);
            marks.executeUpdate();
        }
        try (PreparedStatement student = connection().prepareStatement("DELETE FROM students WHERE id = ?")) {
            student.setInt(1, id);
            student.executeUpdate();
        }
    }

    /** Every division name once, like [A, B]. DISTINCT removes duplicates. */
    public List<String> listDivisions() throws SQLException {
        String sql = "SELECT DISTINCT division FROM students ORDER BY division";
        List<String> divisions = new ArrayList<>();
        try (PreparedStatement statement = connection().prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                divisions.add(rows.getString("division"));
            }
        }
        return divisions;
    }

    /**
     * Students for the report and the Excel export.
     *
     * @param divisionFilter {@code All} returns every student. Any other value matches that division.
     */
    public List<StudentRecord> listStudents(String divisionFilter) throws SQLException {
        return listStudents(divisionFilter, null);
    }

    /**
     * Same as {@link #listStudents(String)}, and each row also carries its mark on {@code day}.
     * {@code day} may be null. The Mark Attendance tab uses this to show which button is already chosen.
     */
    public List<StudentRecord> listStudents(String divisionFilter, LocalDate day) throws SQLException {
        boolean allDivisions = divisionFilter == null || divisionFilter.equals("All");
        try (PreparedStatement statement = connection().prepareStatement(LIST_STUDENTS)) {
            // Fill the three ? marks of LIST_STUDENTS, in order.
            if (day == null) {
                statement.setNull(1, java.sql.Types.DATE);
            } else {
                statement.setDate(1, Date.valueOf(day));
            }
            statement.setInt(2, allDivisions ? 1 : 0);
            statement.setString(3, allDivisions ? "" : divisionFilter);
            return readStudents(statement);
        }
    }

    /**
     * Inserts a mark, or updates it when this student already has a row on that date.
     * <p>
     * This uses a transaction: setAutoCommit(false) means "don't save yet". The find and the
     * insert/update are saved together by commit(). If anything fails, rollback() undoes it.
     */
    public void markAttendance(int studentId, LocalDate date, String status) throws SQLException {
        Connection conn = connection();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Integer existingId = findAttendanceId(conn, studentId, date);
            if (existingId == null) {
                insertAttendance(conn, studentId, date, status);    // first mark for this day
            } else {
                updateAttendance(conn, existingId, status);         // change the day's mark
            }
            conn.commit();
        } catch (SQLException ex) {
            try {
                conn.rollback();
            } catch (SQLException rollbackError) {
                ex.addSuppressed(rollbackError);
            }
            throw ex;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /** True when MySQL refused a row because of a UNIQUE key (error code 1062), for example a repeated roll number. */
    public static boolean isDuplicateKey(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            if (current.getErrorCode() == 1062) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    /**
     * Turns an exception into one short, readable line for the page.
     * For connection problems the useful text is often in the innermost "cause", so we look there too.
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "Unknown database error.";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String rootMessage = oneLine(root.getMessage());
        if (root != error && isConnectionProblem(rootMessage)) {
            return rootMessage;
        }
        String top = oneLine(error.getMessage());
        if (!top.isEmpty()) {
            return top;
        }
        if (!rootMessage.isEmpty()) {
            return rootMessage;
        }
        return error.getClass().getSimpleName();
    }

    private static boolean isConnectionProblem(String message) {
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("connection refused")
                || text.contains("timed out")
                || text.contains("unknown host");
    }

    private static String oneLine(String message) {
        if (message == null) {
            return "";
        }
        return message.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /** The open connection, or an SQLException if MySQL is not reachable. Every query starts here. */
    private Connection connection() throws SQLException {
        if (!isConnected()) {
            throw new SQLException(getLastError());
        }
        return connection;
    }

    /** Runs the query and turns each result row into a StudentRecord object. */
    private List<StudentRecord> readStudents(PreparedStatement statement) throws SQLException {
        List<StudentRecord> students = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                // The names in quotes are the column names (or AS names) from LIST_STUDENTS.
                students.add(new StudentRecord(
                        rows.getInt("id"),
                        rows.getString("roll_number"),
                        rows.getString("name"),
                        rows.getString("division"),
                        rows.getInt("present_days"),
                        rows.getInt("absent_days"),
                        rows.getString("day_status")));
            }
        }
        return students;
    }

    /** The id of this student's attendance row on that date, or null if there is none yet. */
    private Integer findAttendanceId(Connection conn, int studentId, LocalDate date) throws SQLException {
        String sql = "SELECT id FROM attendance WHERE student_id = ? AND attendance_date = ?";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, studentId);
            statement.setDate(2, Date.valueOf(date));
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getInt("id");
                }
            }
        }
        return null;
    }

    private void insertAttendance(Connection conn, int studentId, LocalDate date, String status)
            throws SQLException {
        String sql = "INSERT INTO attendance (student_id, attendance_date, status) VALUES (?, ?, ?)";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setInt(1, studentId);
            statement.setDate(2, Date.valueOf(date));
            statement.setString(3, status);
            statement.executeUpdate();
        }
    }

    private void updateAttendance(Connection conn, int attendanceId, String status) throws SQLException {
        String sql = "UPDATE attendance SET status = ? WHERE id = ?";
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setInt(2, attendanceId);
            statement.executeUpdate();
        }
    }

    /** Removes spaces at both ends. A missing value (null) becomes "". */
    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
