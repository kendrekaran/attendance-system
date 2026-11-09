package attendance;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The values in db.properties. The password may be empty.
 */
public class DbConfig {
    public static final String FILE_NAME = "db.properties";

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;

    public DbConfig(String host, int port, String database, String user, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    /**
     * Reads db.properties. A .properties file is plain text with one {@code key=value} per line,
     * and Java's {@link Properties} class reads it for us. A missing key falls back to a default.
     * Any problem becomes an IOException with a message the page can show.
     */
    public static DbConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Could not read " + file.toAbsolutePath()
                    + ". Start the app from the attendance-system folder.");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        String host = text(properties, "host", "localhost");
        String portText = text(properties, "port", "3306");
        String database = text(properties, "database", "attendance_db");
        String user = text(properties, "user", "root");
        String password = properties.getProperty("password", "");
        if (password == null) {
            password = "";
        }

        if (host.isEmpty() || database.isEmpty() || user.isEmpty()) {
            throw new IOException("db.properties needs a host, a database, and a user.");
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            throw new IOException("Port in db.properties must be a number. Found: " + portText);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("Port in db.properties must be from 1 to 65535. Found: " + port);
        }
        return new DbConfig(host, port, database, user, password);
    }

    /**
     * JDBC URL for this config. The password is not put in the URL.
     * connectTimeout stops a down server from holding up a page request for a long time.
     */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?connectTimeout=3000&socketTimeout=10000"
                + "&sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String location() {
        return host + ":" + port + "/" + database;
    }

    private static String text(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key, fallback);
        if (value == null) {
            return fallback;
        }
        return value.trim();
    }
}
