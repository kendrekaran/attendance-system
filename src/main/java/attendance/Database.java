package attendance;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JDBC access to attendance_db. Every query uses a {@link PreparedStatement}.
 * Text from the form is bound with setters. It is not pasted into the SQL.
 */
public class Database {
    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";

    /*
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

    private final DbConfig config;
    private Connection connection;
    private String lastError;

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

    public String location() {
        if (config == null) {
            return "the database in db.properties";
        }
        return config.location();
    }

    public boolean connect() {
        close();
        if (config == null) {
            return false;
        }
        try {
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

    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            lastError = describe(ex);
        } finally {
            connection = null;
        }
    }

    public void addStudent(String name, String rollNumber, String division) throws SQLException {
        String sql = "INSERT INTO students (name, roll_number, division) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            statement.setString(1, safeTrim(name));
            statement.setString(2, safeTrim(rollNumber));
            statement.setString(3, safeTrim(division));
            statement.executeUpdate();
        }
    }

    /** Changes a student's name, roll number, and division. Returns false if the id is not found. */
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

    /** Deletes a student. Their attendance rows are deleted first. */
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
     * @param divisionFilter {@code All} returns every student. Any other value matches that division.
     */
    public List<StudentRecord> listStudents(String divisionFilter) throws SQLException {
        return listStudents(divisionFilter, null);
    }

    /**
     * Same as {@link #listStudents(String)}, and each row also carries its mark on {@code day}.
     * {@code day} may be null.
     */
    public List<StudentRecord> listStudents(String divisionFilter, LocalDate day) throws SQLException {
        boolean allDivisions = divisionFilter == null || divisionFilter.equals("All");
        try (PreparedStatement statement = connection().prepareStatement(LIST_STUDENTS)) {
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
     */
    public void markAttendance(int studentId, LocalDate date, String status) throws SQLException {
        Connection conn = connection();
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            Integer existingId = findAttendanceId(conn, studentId, date);
            if (existingId == null) {
                insertAttendance(conn, studentId, date, status);
            } else {
                updateAttendance(conn, existingId, status);
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

    private Connection connection() throws SQLException {
        if (!isConnected()) {
            throw new SQLException(getLastError());
        }
        return connection;
    }

    private List<StudentRecord> readStudents(PreparedStatement statement) throws SQLException {
        List<StudentRecord> students = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
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

    private static String safeTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
