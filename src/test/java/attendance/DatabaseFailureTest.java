package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Uses a closed local port so the test does not need MySQL.
 * A failed connect must stay safe for a later call.
 */
class DatabaseFailureTest {
    @Test
    void failedConnectDoesNotCrashLaterCalls() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        DbConfig config = new DbConfig("127.0.0.1", closedPort, "attendance_db", "root", "");
        Database database = new Database(config);
        try {
            assertFalse(database.connect());
            assertFalse(database.isConnected());
            String error = database.getLastError();
            assertTrue(error != null && !error.isBlank());

            assertThrows(SQLException.class, () -> database.addStudent("Asha", "101", "A"));
            assertThrows(SQLException.class, () -> database.listStudents("All"));
            assertThrows(SQLException.class, database::listDivisions);
            assertThrows(SQLException.class,
                    () -> database.markAttendance(1, LocalDate.of(2026, 11, 9), Database.PRESENT));
            assertThrows(SQLException.class, () -> database.checkLogin("admin", "admin123"));
            assertThrows(SQLException.class, () -> database.studentHistory(1));

            database.close();
            assertFalse(database.connect());
        } finally {
            database.close();
        }
    }

    @Test
    void sha256GivesTheSameHexAsMysqlSha2() {
        // SELECT SHA2('abc', 256) in MySQL gives this value.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Database.sha256("abc"));
    }

    @Test
    void describeKeepsAPlainSqlMessageAndPrefersConnectionRefused() {
        assertEquals("Unknown column 'division'",
                Database.describe(new SQLException("Unknown column 'division'")));
        SQLException wrapped = new SQLException("Communications link failure\n\nThe last packet was lost.");
        wrapped.initCause(new ConnectException("Connection refused"));
        assertEquals("Connection refused", Database.describe(wrapped));
    }
}
