package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DbConfigTest {
    @Test
    void readsCommentsAndAnEmptyPassword() throws Exception {
        Path file = Files.createTempFile("db", ".properties");
        try {
            Files.writeString(file, """
                    # local test file
                    host = localhost
                    port = 3307
                    database = sample_db
                    user = student
                    password =
                    """, StandardCharsets.UTF_8);
            DbConfig config = DbConfig.load(file);
            assertEquals("localhost", config.getHost());
            assertEquals(3307, config.getPort());
            assertEquals("sample_db", config.getDatabase());
            assertEquals("student", config.getUser());
            assertEquals("", config.getPassword());
            assertTrue(config.jdbcUrl().startsWith("jdbc:mysql://localhost:3307/sample_db?"));
            assertTrue(config.jdbcUrl().contains("connectTimeout=3000"));
            assertTrue(!config.jdbcUrl().contains("password"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingPasswordKeyIsEmpty() throws Exception {
        Path file = Files.createTempFile("db", ".properties");
        try {
            Files.writeString(file, "host=localhost\nport=3306\ndatabase=attendance_db\nuser=root\n",
                    StandardCharsets.UTF_8);
            assertEquals("", DbConfig.load(file).getPassword());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingFileExplainsWhereToStart() {
        IOException ex = assertThrows(IOException.class,
                () -> DbConfig.load(Path.of("missing-db.properties")));
        assertTrue(ex.getMessage().contains("missing-db.properties"));
    }

    @Test
    void rejectsAPortThatIsNotANumber() throws Exception {
        Path file = Files.createTempFile("db", ".properties");
        try {
            Files.writeString(file, "host=localhost\nport=abc\ndatabase=attendance_db\nuser=root\npassword=\n",
                    StandardCharsets.UTF_8);
            IOException ex = assertThrows(IOException.class, () -> DbConfig.load(file));
            assertTrue(ex.getMessage().contains("Port"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
