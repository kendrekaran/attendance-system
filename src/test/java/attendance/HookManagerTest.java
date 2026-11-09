package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HookManagerTest {
    @Test
    void runsHooksInRegistrationOrder() {
        List<String> calls = new ArrayList<>();
        HookManager manager = new HookManager();
        manager.register(new RecordingHook(calls, "first"));
        manager.register(new RecordingHook(calls, "second"));

        manager.beforeAddStudent("Asha", "101", "A");
        manager.afterAddStudent("Asha", "101", "A");
        manager.beforeMarkAttendance("101", "2026-11-09", "PRESENT");
        manager.afterMarkAttendance("101", "2026-11-09", "PRESENT");
        manager.beforeExport(2);
        manager.afterExport(2, "attendance.xlsx");

        assertEquals(List.of(
                "first beforeAddStudent",
                "second beforeAddStudent",
                "first afterAddStudent",
                "second afterAddStudent",
                "first beforeMarkAttendance",
                "second beforeMarkAttendance",
                "first afterMarkAttendance",
                "second afterMarkAttendance",
                "first beforeExport",
                "second beforeExport",
                "first afterExport",
                "second afterExport"), calls);
    }

    @Test
    void aLaterHookDoesNotRunAfterOneThrows() {
        List<String> calls = new ArrayList<>();
        HookManager manager = new HookManager();
        manager.register(new RecordingHook(calls, "first"));
        manager.register(new Hook() {
            @Override
            public void beforeAddStudent(String name, String rollNumber, String division) {
                throw new IllegalArgumentException("Name cannot be blank.");
            }
        });
        manager.register(new RecordingHook(calls, "third"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.beforeAddStudent(" ", "101", "A"));

        assertEquals("Name cannot be blank.", ex.getMessage());
        assertEquals(List.of("first beforeAddStudent"), calls);
    }

    @Test
    void aThrowingExportHookStopsLaterHooks() {
        List<String> calls = new ArrayList<>();
        HookManager manager = new HookManager();
        manager.register(new Hook() {
            @Override
            public void beforeExport(int rowCount) {
                throw new IllegalArgumentException("stop export");
            }
        });
        manager.register(new RecordingHook(calls, "later"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.beforeExport(4));

        assertEquals("stop export", ex.getMessage());
        assertEquals(List.of(), calls);
    }

    private static final class RecordingHook implements Hook {
        private final List<String> calls;
        private final String name;

        private RecordingHook(List<String> calls, String name) {
            this.calls = calls;
            this.name = name;
        }

        @Override
        public void beforeAddStudent(String name, String rollNumber, String division) {
            calls.add(this.name + " beforeAddStudent");
        }

        @Override
        public void afterAddStudent(String name, String rollNumber, String division) {
            calls.add(this.name + " afterAddStudent");
        }

        @Override
        public void beforeMarkAttendance(String rollNumber, String date, String status) {
            calls.add(this.name + " beforeMarkAttendance");
        }

        @Override
        public void afterMarkAttendance(String rollNumber, String date, String status) {
            calls.add(this.name + " afterMarkAttendance");
        }

        @Override
        public void beforeExport(int rowCount) {
            calls.add(this.name + " beforeExport");
        }

        @Override
        public void afterExport(int rowCount, String filePath) {
            calls.add(this.name + " afterExport");
        }
    }
}
