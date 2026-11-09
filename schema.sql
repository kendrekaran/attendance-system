-- Attendance database for the attendance website.
-- Load it with:  mysql -u root < schema.sql
-- Running this script again drops the tables and recreates the sample rows.
--
-- Database-side hooks: two triggers store division as UPPER(TRIM(division)),
-- one when a student is added and one when a student is edited. Typing "a" is saved as "A".

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

-- One row per student per date. Deleting a student also deletes their rows here.
CREATE TABLE attendance (
    id INT NOT NULL AUTO_INCREMENT,
    student_id INT NOT NULL,
    attendance_date DATE NOT NULL,
    status ENUM('PRESENT', 'ABSENT') NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_attendance_student_date (student_id, attendance_date),
    CONSTRAINT fk_attendance_student
        FOREIGN KEY (student_id) REFERENCES students (id)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- NEW.division is the value being saved.
CREATE TRIGGER students_uppercase_division
BEFORE INSERT ON students
FOR EACH ROW
SET NEW.division = UPPER(TRIM(NEW.division));

CREATE TRIGGER students_uppercase_division_on_update
BEFORE UPDATE ON students
FOR EACH ROW
SET NEW.division = UPPER(TRIM(NEW.division));

-- Sample class. Divisions are written in lowercase on purpose so the trigger uppercases them.
INSERT INTO students (name, roll_number, division) VALUES
    ('Aarav Sharma', '101', 'a'),
    ('Ananya Iyer', '102', 'a'),
    ('Rohan Deshmukh', '103', 'a'),
    ('Priya Kulkarni', '104', 'a'),
    ('Vivaan Patil', '105', 'a'),
    ('Sneha Joshi', '106', 'a'),
    ('Siddharth Rao', '201', 'b'),
    ('Kavya Reddy', '202', 'b'),
    ('Omkar Jadhav', '203', 'b'),
    ('Riya Gupta', '204', 'b'),
    ('Yash Thakur', '205', 'b'),
    ('Tanvi Bhosale', '206', 'b');

-- One week of attendance: everyone is marked present first...
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-02', 'PRESENT' FROM students;
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-03', 'PRESENT' FROM students;
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-04', 'PRESENT' FROM students;
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-05', 'PRESENT' FROM students;
INSERT INTO attendance (student_id, attendance_date, status)
SELECT id, '2026-11-06', 'PRESENT' FROM students;

-- ...then these absences are filled in. After loading:
--   103 Rohan Deshmukh  3 present, 2 absent  -> 60.0 (below 75%)
--   203 Omkar Jadhav    3 present, 2 absent  -> 60.0 (below 75%)
--   102 Ananya Iyer     4 present, 1 absent  -> 80.0
--   205 Yash Thakur     4 present, 1 absent  -> 80.0
--   everyone else       5 present, 0 absent  -> 100.0
UPDATE attendance SET status = 'ABSENT'
WHERE student_id = (SELECT id FROM students WHERE roll_number = '103')
  AND attendance_date IN ('2026-11-03', '2026-11-05');

UPDATE attendance SET status = 'ABSENT'
WHERE student_id = (SELECT id FROM students WHERE roll_number = '203')
  AND attendance_date IN ('2026-11-02', '2026-11-06');

UPDATE attendance SET status = 'ABSENT'
WHERE student_id = (SELECT id FROM students WHERE roll_number = '102')
  AND attendance_date = '2026-11-04';

UPDATE attendance SET status = 'ABSENT'
WHERE student_id = (SELECT id FROM students WHERE roll_number = '205')
  AND attendance_date = '2026-11-05';
