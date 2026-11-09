package attendance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FutureDateHookTest {
    private final FutureDateHook hook = new FutureDateHook();

    @Test
    void allowsTodayAndPastDays() {
        assertDoesNotThrow(() -> hook.beforeMarkAttendance("101", LocalDate.now().toString(), "PRESENT"));
        assertDoesNotThrow(() -> hook.beforeMarkAttendance("101", "2026-11-02", "ABSENT"));
    }

    @Test
    void rejectsTomorrow() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> hook.beforeMarkAttendance("101", tomorrow, "PRESENT"));
        assertEquals("Attendance cannot be marked for a future date.", ex.getMessage());
    }
}
