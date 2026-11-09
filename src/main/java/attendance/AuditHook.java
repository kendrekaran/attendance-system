package attendance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Appends one line to hooks.log for every hook call.
 * The file is created in the working directory.
 */
public class AuditHook implements Hook {
    public static final String FILE_NAME = "hooks.log";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logFile;

    public AuditHook() {
        this(Path.of(FILE_NAME));
    }

    public AuditHook(Path logFile) {
        this.logFile = logFile;
    }

    public Path getLogFile() {
        return logFile;
    }

    @Override
    public void beforeAddStudent(String name, String rollNumber, String division) {
        write("beforeAddStudent",
                "name=" + oneLine(name) + " roll=" + oneLine(rollNumber) + " division=" + oneLine(division));
    }

    @Override
    public void afterAddStudent(String name, String rollNumber, String division) {
        write("afterAddStudent",
                "name=" + oneLine(name) + " roll=" + oneLine(rollNumber) + " division=" + oneLine(division));
    }

    @Override
    public void beforeUpdateStudent(String name, String rollNumber, String division) {
        write("beforeUpdateStudent",
                "name=" + oneLine(name) + " roll=" + oneLine(rollNumber) + " division=" + oneLine(division));
    }

    @Override
    public void afterUpdateStudent(String name, String rollNumber, String division) {
        write("afterUpdateStudent",
                "name=" + oneLine(name) + " roll=" + oneLine(rollNumber) + " division=" + oneLine(division));
    }

    @Override
    public void beforeDeleteStudent(String name, String rollNumber) {
        write("beforeDeleteStudent", "name=" + oneLine(name) + " roll=" + oneLine(rollNumber));
    }

    @Override
    public void afterDeleteStudent(String name, String rollNumber) {
        write("afterDeleteStudent", "name=" + oneLine(name) + " roll=" + oneLine(rollNumber));
    }

    @Override
    public void beforeMarkAttendance(String rollNumber, String date, String status) {
        write("beforeMarkAttendance",
                "roll=" + oneLine(rollNumber) + " date=" + oneLine(date) + " status=" + oneLine(status));
    }

    @Override
    public void afterMarkAttendance(String rollNumber, String date, String status) {
        write("afterMarkAttendance",
                "roll=" + oneLine(rollNumber) + " date=" + oneLine(date) + " status=" + oneLine(status));
    }

    @Override
    public void beforeExport(int rowCount) {
        write("beforeExport", "rows=" + rowCount);
    }

    @Override
    public void afterExport(int rowCount, String filePath) {
        write("afterExport", "rows=" + rowCount + " file=" + oneLine(filePath));
    }

    private void write(String method, String detail) {
        String line = LocalDateTime.now().format(TIME) + " " + method + " " + detail;
        try {
            Files.writeString(
                    logFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // A log problem should not cancel the user's action.
            System.err.println("Could not write " + logFile + ": " + ex.getMessage());
        }
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
