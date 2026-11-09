package attendance;

import java.time.LocalDate;

/**
 * Stops attendance from being marked for a day that has not happened yet.
 */
public class FutureDateHook implements Hook {
    @Override
    public void beforeMarkAttendance(String rollNumber, String date, String status) {
        LocalDate day = LocalDate.parse(date);
        if (day.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Attendance cannot be marked for a future date.");
        }
    }
}
