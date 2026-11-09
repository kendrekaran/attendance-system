package attendance;

/**
 * A callback around one of the website's actions.
 * <p>
 * The default methods do nothing, so a hook only overrides what it needs.
 * Throw {@link IllegalArgumentException} from a {@code before} method to cancel
 * the action. The page shows {@code getMessage()} and nothing is saved.
 */
public interface Hook {
    default void beforeAddStudent(String name, String rollNumber, String division) {
    }

    default void afterAddStudent(String name, String rollNumber, String division) {
    }

    default void beforeUpdateStudent(String name, String rollNumber, String division) {
    }

    default void afterUpdateStudent(String name, String rollNumber, String division) {
    }

    default void beforeDeleteStudent(String name, String rollNumber) {
    }

    default void afterDeleteStudent(String name, String rollNumber) {
    }

    default void beforeMarkAttendance(String rollNumber, String date, String status) {
    }

    default void afterMarkAttendance(String rollNumber, String date, String status) {
    }

    default void beforeExport(int rowCount) {
    }

    default void afterExport(int rowCount, String filePath) {
    }
}
