-- Attendance database for the desktop app.
-- Load it with:  mysql -u root < schema.sql
-- Running this script again drops the tables and recreates the sample rows.
--
-- Database-side hook: students_uppercase_division runs BEFORE INSERT
-- and stores division as UPPER(TRIM(division)). Typing "a" is saved as "A".

CREATE DATABASE IF NOT EXISTS attendance_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE attendance_db;

DROP TABLE IF EXISTS attendance;
DROP TABLE IF EXISTS students;

CREATE TABLE students (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    roll_number VARCHAR(50) NOT NULL,
    division VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_students_roll (roll_number)
) ENGINE=InnoDB;

CREATE TABLE attendance (
    id INT NOT NULL AUTO_INCREMENT,
    student_id INT NOT NULL,
    attendance_date DATE NOT NULL,
    status ENUM('PRESENT', 'ABSENT') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_attendance_student_date (student_id, attendance_date),
    CONSTRAINT fk_attendance_student
        FOREIGN KEY (student_id) REFERENCES students (id)
) ENGINE=InnoDB;

-- One row is inserted or updated per student per date from the app.
-- This trigger is the database-side hook. NEW.division is the value being inserted.
CREATE TRIGGER students_uppercase_division
BEFORE INSERT ON students
FOR EACH ROW
SET NEW.division = UPPER(TRIM(NEW.division));

-- Divisions are written in lowercase on purpose so the trigger can uppercase them.
-- After loading:
--   101 Asha Rao  A  2 present, 1 absent  -> 66.7
--   102 Ben Cole  A  no marks             -> em dash
--   103 Esha Nair A  2 present, 0 absent  -> 100.0
--   201 Cara Diaz B  1 present, 0 absent  -> 100.0
--   202 Dev Shah  B  0 present, 1 absent  -> 0.0
INSERT INTO students (name, roll_number, division) VALUES
    ('Asha Rao', '101', 'a'),
    ('Ben Cole', '102', 'a'),
    ('Esha Nair', '103', 'a'),
    ('Cara Diaz', '201', 'b'),
    ('Dev Shah', '202', 'b');

INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-03', 'PRESENT' FROM students WHERE roll_number = '101';
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-04', 'PRESENT' FROM students WHERE roll_number = '101';
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-05', 'ABSENT' FROM students WHERE roll_number = '101';

INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-03', 'PRESENT' FROM students WHERE roll_number = '103';
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-04', 'PRESENT' FROM students WHERE roll_number = '103';

INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-03', 'PRESENT' FROM students WHERE roll_number = '201';

INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-03', 'ABSENT' FROM students WHERE roll_number = '202';
