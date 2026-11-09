package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StudentRecordTest {
    @Test
    void noMarkedDaysIsAnEmDash() {
        assertEquals("\u2014", StudentRecord.attendancePercent(0, 0));
        assertEquals(StudentRecord.NO_MARKS, new StudentRecord(1, "102", "Ben", "A", 0, 0).attendancePercent());
        assertFalse(StudentRecord.attendancePercent(0, 0).contains("0"));
        assertFalse(StudentRecord.attendancePercent(0, 0).contains("%"));
    }

    @Test
    void percentageUsesOneDecimal() {
        assertEquals("100.0", StudentRecord.attendancePercent(2, 0));
        assertEquals("0.0", StudentRecord.attendancePercent(0, 4));
        assertEquals("50.0", StudentRecord.attendancePercent(1, 1));
        assertEquals("66.7", StudentRecord.attendancePercent(2, 1));
        assertEquals("33.3", StudentRecord.attendancePercent(1, 2));
        assertTrue(StudentRecord.attendancePercent(2, 1).matches("\\d+\\.\\d"));
    }
}
