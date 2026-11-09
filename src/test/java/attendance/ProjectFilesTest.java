package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectFilesTest {
    @Test
    void schemaCreatesTheDatabaseTriggerAndSampleRows() throws Exception {
        String sql = Files.readString(Path.of("schema.sql"), StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS attendance_db"));
        assertTrue(sql.contains("CREATE TABLE students"));
        assertTrue(sql.contains("CREATE TABLE attendance"));
        assertTrue(sql.contains("CREATE TABLE users"));
        assertTrue(sql.contains("SHA2(CONCAT("));
        assertTrue(sql.contains("BEFORE INSERT ON students"));
        assertTrue(sql.contains("BEFORE UPDATE ON students"));
        assertTrue(sql.contains("SET NEW.division = UPPER(TRIM(NEW.division))"));
        assertTrue(sql.contains("ON DELETE CASCADE"));
        assertEquals(2, countOf(sql, "CREATE TRIGGER"));
        assertTrue(sql.contains("INSERT INTO students"));
        assertTrue(sql.contains("INSERT INTO attendance"));
        assertTrue(sql.contains("'a'"));
        assertTrue(sql.contains("'b'"));
    }

    @Test
    void propertiesMatchTheLocalDefaults() throws Exception {
        String text = Files.readString(Path.of("db.properties"), StandardCharsets.UTF_8);
        assertTrue(text.contains("#"));
        DbConfig config = DbConfig.load(Path.of("db.properties"));
        assertEquals("localhost", config.getHost());
        assertEquals(3306, config.getPort());
        assertEquals("attendance_db", config.getDatabase());
        assertEquals("root", config.getUser());
        assertEquals("", config.getPassword());
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int found = text.indexOf(token, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + token.length();
        }
    }
}
