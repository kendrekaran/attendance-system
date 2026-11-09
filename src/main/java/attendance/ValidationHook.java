package attendance;

/**
 * Stops an add or an edit when the name, roll number, or division is blank.
 */
public class ValidationHook implements Hook {
    @Override
    public void beforeAddStudent(String name, String rollNumber, String division) {
        requireText(name, "Name");
        requireText(rollNumber, "Roll number");
        requireText(division, "Division");
    }

    @Override
    public void beforeUpdateStudent(String name, String rollNumber, String division) {
        requireText(name, "Name");
        requireText(rollNumber, "Roll number");
        requireText(division, "Division");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank.");
        }
    }
}
