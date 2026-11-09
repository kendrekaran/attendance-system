package attendance;

/**
 * A callback around one of the website's actions.
 * <p>
 * The default methods do nothing, so a hook only overrides what it needs.
 * Throw {@link IllegalArgumentException} from a {@code before} method to cancel
 * the action. The page shows {@code getMessage()} and nothing is saved.
 * <p>
 * Beginner notes:
 * <ul>
 *     <li>An interface is a list of methods a class promises to have.</li>
 *     <li>{@code default} methods already have a body (here an empty one), so a class that
 *         {@code implements Hook} only writes the methods it cares about.
 *         For example ValidationHook only writes beforeAddStudent and beforeUpdateStudent.</li>
 *     <li>"before" methods run before the database is changed and can stop the action.
 *         "after" methods run after it is saved, for side effects like writing a log.</li>
 * </ul>
 */
public interface Hook {
    // Students tab: Add Student
    default void beforeAddStudent(String name, String rollNumber, String division) {
    }

    default void afterAddStudent(String name, String rollNumber, String division) {
    }

    // Students tab: Edit, then Update Student
    default void beforeUpdateStudent(String name, String rollNumber, String division) {
    }

    default void afterUpdateStudent(String name, String rollNumber, String division) {
    }

    // Students tab: Delete
    default void beforeDeleteStudent(String name, String rollNumber) {
    }

    default void afterDeleteStudent(String name, String rollNumber) {
    }

    // Mark Attendance tab: called once for every student when Save Attendance is clicked
    default void beforeMarkAttendance(String rollNumber, String date, String status) {
    }

    default void afterMarkAttendance(String rollNumber, String date, String status) {
    }

    // Attendance Report tab: Export to Excel
    default void beforeExport(int rowCount) {
    }

    default void afterExport(int rowCount, String filePath) {
    }
}
