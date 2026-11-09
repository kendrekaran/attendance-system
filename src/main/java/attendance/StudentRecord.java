package attendance;

import java.util.Locale;

/**
 * One student plus how many days were marked present or absent.
 * <p>
 * This is a plain "data class": it only holds values and has getters to read them.
 * The fields are {@code final}, so a StudentRecord cannot change after it is created.
 * Database makes these objects from SQL rows, and WebServer turns them into JSON for the page.
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

    // Two constructors: this shorter one is used when no date was chosen.
    // this(...) calls the longer constructor below with dayStatus = null.
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
            return NO_MARKS;    // avoid dividing by zero for a new student
        }
        // 100.0 (not 100) makes this a decimal division. 3 * 100 / 5 would be integer maths.
        // Example: 3 present, 2 absent -> 3 * 100.0 / 5 = 60.0
        double percent = (presentDays * 100.0) / markedDays;
        // "%.1f" means one digit after the dot. Locale.US makes sure the dot is a "." everywhere.
        return String.format(Locale.US, "%.1f", percent);
    }
}
