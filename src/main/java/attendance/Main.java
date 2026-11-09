package attendance;

import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;

/**
 * Registers the built-in hooks and starts the attendance website.
 * <p>
 * The port is 8080. Pass another port as the first argument, for example {@code 9090}.
 */
public class Main {
    public static final String HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        HookManager hooks = new HookManager();
        // Checks are first, so a blank form or a future date stops before AuditHook writes a line.
        hooks.register(new ValidationHook());
        hooks.register(new FutureDateHook());
        hooks.register(new AuditHook());

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException ex) {
                System.err.println("Port must be a number. Found: " + args[0]);
                System.exit(1);
            }
        }

        Database database = openDatabase();
        WebServer server = new WebServer(database, hooks);
        try {
            server.start(HOST, port);
        } catch (BindException ex) {
            System.err.println("Port " + port + " is already in use. Stop the other program or pass another port.");
            System.exit(1);
        } catch (IOException ex) {
            System.err.println("Could not start the website: " + ex.getMessage());
            System.exit(1);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        System.out.println("Attendance website: http://localhost:" + server.getPort());
        if (database.connect()) {
            System.out.println("MySQL connected at " + database.location() + ".");
        } else {
            System.out.println("MySQL is not reachable at " + database.location() + ": " + database.getLastError());
            System.out.println("The page will say so. Start MySQL, load schema.sql, then click Retry.");
        }
        System.out.println("Log in with username admin and password admin123.");
        System.out.println("Press Ctrl+C to stop.");
    }

    private static Database openDatabase() {
        try {
            DbConfig config = DbConfig.load(Path.of(DbConfig.FILE_NAME));
            return new Database(config);
        } catch (IOException ex) {
            return new Database(ex.getMessage());
        }
    }
}
