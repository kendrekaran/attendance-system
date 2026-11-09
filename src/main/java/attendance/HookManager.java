package attendance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs registered hooks from first to last.
 * If one throws {@link IllegalArgumentException}, later hooks are not called.
 */
public class HookManager {
    private final List<Hook> hooks = new ArrayList<>();

    public void register(Hook hook) {
        hooks.add(Objects.requireNonNull(hook, "hook"));
    }

    public void beforeAddStudent(String name, String rollNumber, String division) {
        for (Hook hook : hooks) {
            hook.beforeAddStudent(name, rollNumber, division);
        }
    }

    public void afterAddStudent(String name, String rollNumber, String division) {
        for (Hook hook : hooks) {
            hook.afterAddStudent(name, rollNumber, division);
        }
    }

    public void beforeMarkAttendance(String rollNumber, String date, String status) {
        for (Hook hook : hooks) {
            hook.beforeMarkAttendance(rollNumber, date, status);
        }
    }

    public void afterMarkAttendance(String rollNumber, String date, String status) {
        for (Hook hook : hooks) {
            hook.afterMarkAttendance(rollNumber, date, status);
        }
    }

    public void beforeExport(int rowCount) {
        for (Hook hook : hooks) {
            hook.beforeExport(rowCount);
        }
    }

    public void afterExport(int rowCount, String filePath) {
        for (Hook hook : hooks) {
            hook.afterExport(rowCount, filePath);
        }
    }
}
