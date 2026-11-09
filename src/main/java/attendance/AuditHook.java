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
 * <p>
 * This is an "audit log": a record of who changed what and when. Each line looks like
 * {@code 2026-11-10 14:02:33 afterAddStudent name=Meera Iyer roll=107 division=B}.
 * It is registered last in Main, so it only logs actions that passed the checks.
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

    /** Adds one line to the end of hooks.log: the time, the hook method's name, and the details. */
    private void write(String method, String detail) {
        String line = LocalDateTime.now().format(TIME) + " " + method + " " + detail;
        try {
            // CREATE makes the file if it is missing. APPEND adds to the end instead of replacing it.
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

    // Replaces line breaks with spaces, so a name typed with Enter can't break the one-line-per-call format.
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
