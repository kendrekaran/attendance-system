# Student Attendance System

A small attendance website for a class. It stores students and each day's present or absent mark in MySQL, shows each student's attendance percentage, and downloads the report as an Excel file. A plain Java program serves one HTML page on your own computer with the JDK's built-in HTTP server. It uses no web framework.

The teacher logs in first. The default login is username `admin` and password `admin123`. Logout is in the top bar. After login the page has three tabs:

- **Students**: add a student (name, roll number, division), edit a student, or delete a student. Deleting a student also deletes their attendance.
- **Mark Attendance**: pick a date and a division, choose Present or Absent for each student, and click Save Attendance. Mark All Present fills the whole list. Saving the same date again changes that day's marks instead of adding new ones.
- **Attendance Report**: present days, absent days, and attendance % for every student. Students below 75% are shown in red. View shows one student's attendance date by date. Export to Excel downloads the rows for the chosen division.

## Login

Teacher accounts are in the `users` table. The password is never stored as plain text: each user has a random `salt`, and `password_hash` is SHA-256 of salt + password. At login the server hashes what was typed the same way and compares the two hashes.

After a correct login the server makes a random token, remembers which user it belongs to, and sends it to the browser in an `HttpOnly` cookie called `session`. Every request except the page itself and the login must carry that cookie, or the server answers "Please log in first." Logout forgets the token. Restarting the server logs everyone out.

To add another teacher, run this in MySQL with your own username, salt, and password:

```
INSERT INTO users (username, salt, password_hash)
VALUES ('teacher2', 'pick-any-salt', SHA2(CONCAT('pick-any-salt', 'their-password'), 256));
```

## Setup

Install Java 17 or newer, Maven, and MySQL. Create the database and the sample students:

```
mysql -u root < schema.sql
```

The sample has the `admin` login and 12 students in divisions A and B, with one week of attendance (2 to 6 November 2026). Two students, roll 103 and roll 203, are below 75%. Running `schema.sql` again drops the tables and restores the sample.

Connection settings live in `db.properties` in this folder: `localhost`, port `3306`, database `attendance_db`, user `root`, and an empty password. The server reads that file from the folder it is started in, so run it from here. Edit the file if your MySQL account is different.

## Run

From this folder:

```
mvn -q exec:java
```

Then open http://localhost:8080 in a browser and log in as `admin` / `admin123`. The terminal prints whether MySQL connected. Press Ctrl+C to stop the server.

To use another port, pass it as an argument:

```
mvn -q exec:java -Dexec.args="9090"
```

The server only listens on this computer (127.0.0.1), so other machines cannot open the page.

If MySQL is stopped or `db.properties` is wrong, the page still opens and shows a red message saying MySQL cannot be reached. Start MySQL, load `schema.sql` if you have not yet, and switch tabs to try again.

Tests do not need a database:

```
mvn -q test
```

`hooks.log` appears in this folder as you add, edit, or delete students, mark attendance, and export.

## Hooks

A hook is a small class that runs just before or just after an action: adding, editing, or deleting a student, marking attendance, or exporting. Hooks are a way to add a check or a side effect without changing the server code. `HookManager` calls the registered hooks in order. From a `before` method, throw `IllegalArgumentException` to stop the action. The page shows that message and nothing is written to MySQL or to the spreadsheet.

`Main` registers three hooks, in this order:

1. `ValidationHook` rejects a blank name, roll number, or division when a student is added or edited.
2. `FutureDateHook` rejects attendance for a date after today. When a whole class is saved, every mark is checked first, so one bad mark saves nothing.
3. `AuditHook` appends one line to `hooks.log` for each hook call.

The checks come first, so a rejected action never reaches `AuditHook`.

To add another hook, write a class that implements `Hook` and override only the methods you need. The others already do nothing. Then register it in `Main` beside the other `hooks.register(...)` lines.

MySQL has database-side hooks as well. The triggers `students_uppercase_division` (`BEFORE INSERT`) and `students_uppercase_division_on_update` (`BEFORE UPDATE`) set `division = UPPER(TRIM(division))`. A division typed as ` a ` is stored as `A`.

## Percentage and export

Attendance % is present days / marked days × 100, with one decimal place. Marked days are present days plus absent days. A student with no marked days is shown as an em dash (—), not as 0%.

Export to Excel downloads `attendance.xlsx`, or `attendance-division-A.xlsx` when one division is selected. The columns are Roll No, Name, Division, Present Days, Absent Days, and Attendance %.

## Code

All classes are in the `attendance` package.

- `Main` registers the hooks and starts the server.
- `WebServer` serves the page and the JSON requests it makes, and checks the login session.
- `Database` runs every query with `PreparedStatement` and checks passwords.
- `StudentRecord` holds one row and works out the percentage.
- `ExcelExporter` builds the workbook with Apache POI.
- `DbConfig` reads `db.properties`.
- `Hook`, `HookManager`, `ValidationHook`, `FutureDateHook`, and `AuditHook` are the hooks.

The page is `src/main/resources/web/index.html`. Its CSS and JavaScript are inside the same file.
