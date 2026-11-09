package attendance;

/**
 * Stops an add or an edit when the name, roll number, or division is blank.
 * <p>
 * It is registered first in Main, so a bad form is rejected before anything else runs.
 * Throwing IllegalArgumentException is how a hook says "no": WebServer catches it and
 * sends the message (for example "Name cannot be blank.") back to the page.
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

    // trim() removes spaces, so "   " counts as blank too.
    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank.");
        }
    }
}
