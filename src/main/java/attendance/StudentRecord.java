package attendance;

import java.util.Locale;

/**
 * One student plus how many days were marked present or absent.
 */
public class StudentRecord {
    /** Shown when a student has no attendance rows yet. This is an em dash, not 0%. */
    public static final String NO_MARKS = "\u2014";

    private final int id;
    private final String rollNumber;
    private final String name;
    private final String division;
    private final int presentDays;
    private final int absentDays;
    private final String dayStatus;

    public StudentRecord(int id, String rollNumber, String name, String division,
                         int presentDays, int absentDays) {
        this(id, rollNumber, name, division, presentDays, absentDays, null);
    }

    /**
     * @param dayStatus PRESENT, ABSENT, or null when the chosen day is not marked
     */
    public StudentRecord(int id, String rollNumber, String name, String division,
                         int presentDays, int absentDays, String dayStatus) {
        this.id = id;
        this.rollNumber = rollNumber;
        this.name = name;
        this.division = division;
        this.presentDays = presentDays;
        this.absentDays = absentDays;
        this.dayStatus = dayStatus;
    }

    public String getDayStatus() {
        return dayStatus;
    }

    public int getId() {
        return id;
    }

    public String getRollNumber() {
        return rollNumber;
    }

    public String getName() {
        return name;
    }

    public String getDivision() {
        return division;
    }

    public int getPresentDays() {
        return presentDays;
    }

    public int getAbsentDays() {
        return absentDays;
    }

    public String attendancePercent() {
        return attendancePercent(presentDays, absentDays);
    }

    /**
     * Present days / marked days × 100, with one decimal.
     * Marked days are present + absent. No marked days returns an em dash.
     */
    public static String attendancePercent(int presentDays, int absentDays) {
        int markedDays = presentDays + absentDays;
        if (markedDays <= 0) {
            return NO_MARKS;
        }
        double percent = (presentDays * 100.0) / markedDays;
        return String.format(Locale.US, "%.1f", percent);
    }
}
