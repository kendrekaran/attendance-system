# Student attendance website

A small attendance website for a class. It stores students and each day's present or absent mark in MySQL, shows an attendance percentage, and downloads the rows on screen as an Excel file. A plain Java program serves one HTML page on your own computer with the JDK's built-in HTTP server. It uses no web framework.

On the page you can add a student (name, roll number, division), filter the list by division or All, pick a date, select a student, and mark them Present or Absent. Marking the same student on the same date again changes that day's mark instead of adding a second one. Export Excel downloads the rows in the current filter.

## Setup

Install Java 17 or newer, Maven, and MySQL. Create the database and the sample students:

```
mysql -u root < schema.sql
```

The sample has five students in divisions A and B, with some attendance already marked. Running `schema.sql` again drops both tables and restores the sample.

Connection settings live in `db.properties` in this folder: `localhost`, port `3306`, database `attendance_db`, user `root`, and an empty password. The server reads that file from the folder it is started in, so run it from here. Edit the file if your MySQL account is different.

## Run

From this folder:

```
mvn -q exec:java
```

Then open http://localhost:8080 in a browser. The terminal prints whether MySQL connected. Press Ctrl+C to stop the server.

To use another port, pass it as an argument:

```
mvn -q exec:java -Dexec.args="9090"
```

The server only listens on this computer (127.0.0.1), so other machines cannot open the page.

If MySQL is stopped or `db.properties` is wrong, the page still opens and shows a red notice saying MySQL cannot be reached. Nothing is saved while that notice is showing. Start MySQL, load `schema.sql` if you have not yet, and click Retry. The server keeps running the whole time.

Tests do not need a database:

```
mvn -q test
```

`hooks.log` appears in this folder as you add students, mark attendance, and export.

## Hooks

A hook is a small class that runs just before or just after an action: adding a student, marking attendance, or exporting. Hooks are a way to add a check or a side effect without changing the server code. `HookManager` calls the registered hooks in order. From a `before` method, throw `IllegalArgumentException` to stop the action. The page shows that message and nothing is written to MySQL or to the spreadsheet.

`ValidationHook` rejects a blank name, roll number, or division. `AuditHook` appends one line to `hooks.log` for each hook call. `Main` registers validation first, then audit, so a blank form is rejected before an audit line is written. Register a hook earlier if it should see those attempts.

To add another hook, write a class that implements `Hook` and override only the methods you need. The others already do nothing. Then register it in `Main` beside the other `hooks.register(...)` lines:

```java
public class RejectFutureDateHook implements Hook {
    @Override
    public void beforeMarkAttendance(String rollNumber, String date, String status) {
        if (java.time.LocalDate.parse(date).isAfter(java.time.LocalDate.now())) {
            throw new IllegalArgumentException("Date cannot be in the future.");
        }
    }
}
```

```java
hooks.register(new RejectFutureDateHook());
```

MySQL has one database-side hook as well. The trigger `students_uppercase_division` runs `BEFORE INSERT` on `students` and sets `division = UPPER(TRIM(division))`. A division typed as ` a ` is stored as `A`.

## Percentage and export

Attendance % is present days / marked days × 100, with one decimal place. Marked days are present days plus absent days. A student with no marked days is shown as an em dash (—), not as 0%.

Export Excel downloads `attendance.xlsx`, or `attendance-division-A.xlsx` when one division is selected. The columns are Roll No, Name, Division, Present Days, Absent Days, and Attendance %.

## Code

All classes are in the `attendance` package.

- `Main` registers the hooks and starts the server.
- `WebServer` serves the page and the JSON requests it makes.
- `Database` runs every query with `PreparedStatement`.
- `StudentRecord` holds one row and works out the percentage.
- `ExcelExporter` builds the workbook with Apache POI.
- `DbConfig` reads `db.properties`.
- `Hook`, `HookManager`, `ValidationHook`, and `AuditHook` are the hooks.

The page is `src/main/resources/web/index.html`.
