package attendance;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs registered hooks from first to last.
 * If one throws {@link IllegalArgumentException}, later hooks are not called.
 * <p>
 * WebServer only talks to this class, never to each hook. That way new hooks can be added
 * in Main without changing WebServer. Every method below is the same pattern: loop over
 * the list and call the same method on each hook. If a hook throws, the loop stops and the
 * exception goes back to WebServer, which shows the message on the page.
 */
public class HookManager {
    private final List<Hook> hooks = new ArrayList<>();

    /** Adds a hook to the end of the list. requireNonNull stops a null hook early with a clear error. */
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

    public void beforeUpdateStudent(String name, String rollNumber, String division) {
        for (Hook hook : hooks) {
            hook.beforeUpdateStudent(name, rollNumber, division);
        }
    }

    public void afterUpdateStudent(String name, String rollNumber, String division) {
        for (Hook hook : hooks) {
            hook.afterUpdateStudent(name, rollNumber, division);
        }
    }

    public void beforeDeleteStudent(String name, String rollNumber) {
        for (Hook hook : hooks) {
            hook.beforeDeleteStudent(name, rollNumber);
        }
    }

    public void afterDeleteStudent(String name, String rollNumber) {
        for (Hook hook : hooks) {
            hook.afterDeleteStudent(name, rollNumber);
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
