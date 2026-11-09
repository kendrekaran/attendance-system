package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditHookTest {
    @Test
    void defaultFileIsHooksLogInTheWorkingDirectory() {
        AuditHook hook = new AuditHook();
        assertEquals(Path.of("hooks.log").toAbsolutePath(), hook.getLogFile().toAbsolutePath());
    }

    @Test
    void appendsOneLinePerHookCall() throws Exception {
        Path log = Files.createTempFile("hooks", ".log");
        try {
            AuditHook hook = new AuditHook(log);
            hook.beforeAddStudent("Asha\nRao", "101", "A");
            hook.afterAddStudent("Asha Rao", "101", "A");
            hook.beforeMarkAttendance("101", "2026-11-09", "PRESENT");
            hook.afterMarkAttendance("101", "2026-11-09", "ABSENT");
            hook.beforeExport(2);
            hook.afterExport(2, "/tmp/attendance.xlsx");

            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            assertEquals(6, lines.size());
            assertTrue(lines.get(0).contains("beforeAddStudent"));
            assertTrue(lines.get(0).contains("name=Asha Rao"));
            assertTrue(lines.get(1).contains("afterAddStudent"));
            assertTrue(lines.get(2).contains("beforeMarkAttendance"));
            assertTrue(lines.get(2).contains("status=PRESENT"));
            assertTrue(lines.get(3).contains("afterMarkAttendance"));
            assertTrue(lines.get(4).contains("beforeExport"));
            assertTrue(lines.get(4).contains("rows=2"));
            assertTrue(lines.get(5).contains("afterExport"));
            assertTrue(lines.get(5).contains("file=/tmp/attendance.xlsx"));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    @Test
    void managerWritesOneAuditLinePerCall() throws Exception {
        Path log = Files.createTempFile("hooks", ".log");
        try {
            HookManager manager = new HookManager();
            manager.register(new AuditHook(log));
            manager.beforeMarkAttendance("101", "2026-11-09", "PRESENT");
            manager.afterMarkAttendance("101", "2026-11-09", "PRESENT");
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
