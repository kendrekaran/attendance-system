package attendance;

import java.time.LocalDate;

/**
 * Stops attendance from being marked for a day that has not happened yet.
 * <p>
 * It only overrides beforeMarkAttendance, because dates matter only when marking attendance.
 * The date arrives as text like "2026-11-10", which LocalDate.parse turns into a date object.
 */
public class FutureDateHook implements Hook {
    @Override
    public void beforeMarkAttendance(String rollNumber, String date, String status) {
        LocalDate day = LocalDate.parse(date);
        // isAfter(today) is true for tomorrow and later. Today and past days are allowed.
        if (day.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Attendance cannot be marked for a future date.");
        }
    }
}
