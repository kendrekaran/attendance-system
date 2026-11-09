package attendance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ValidationHookTest {
    private final ValidationHook hook = new ValidationHook();

    @Test
    void rejectsABlankName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> hook.beforeAddStudent("   ", "101", "A"));
        assertEquals("Name cannot be blank.", ex.getMessage());
    }

    @Test
    void rejectsABlankRollNumber() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> hook.beforeAddStudent("Asha", null, "A"));
        assertEquals("Roll number cannot be blank.", ex.getMessage());
    }

    @Test
    void rejectsABlankDivision() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> hook.beforeAddStudent("Asha", "101", "\t"));
        assertEquals("Division cannot be blank.", ex.getMessage());
    }

    @Test
    void acceptsACompleteStudent() {
        assertDoesNotThrow(() -> hook.beforeAddStudent(" Asha ", "101", "A"));
    }

    @Test
    void checksEditsTheSameWayAsAdds() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> hook.beforeUpdateStudent("Asha", "", "A"));
        assertEquals("Roll number cannot be blank.", ex.getMessage());
        assertDoesNotThrow(() -> hook.beforeUpdateStudent("Asha", "101", "A"));
    }

    @Test
    void doesNotBlockMarkingOrExport() {
        assertDoesNotThrow(() -> hook.beforeMarkAttendance("", "", ""));
        assertDoesNotThrow(() -> hook.beforeExport(0));
    }
}
