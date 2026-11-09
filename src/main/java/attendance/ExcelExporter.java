package attendance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes the rows currently shown on the page to an .xlsx workbook.
 * <p>
 * It uses Apache POI, a library for Excel files (added in pom.xml). An Excel file is built
 * like a tree: Workbook (the file) has Sheets (tabs at the bottom), a Sheet has Rows,
 * and a Row has Cells. Rows and columns are counted from 0, so row 0 is the header.
 */
public class ExcelExporter {
    // The header row, left to right.
    public static final String[] COLUMNS = {
            "Roll No",
            "Name",
            "Division",
            "Present Days",
            "Absent Days",
            "Attendance %"
    };

    /** Saves the workbook to a file on disk. The tests use this one. */
    public static void write(Path file, List<StudentRecord> students) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out, students);
        }
    }

    /**
     * Writes the workbook to {@code out}. The caller closes the stream.
     * WebServer passes a stream in memory and then sends those bytes to the browser as a download.
     */
    public static void write(OutputStream out, List<StudentRecord> students) throws IOException {
        // XSSFWorkbook is the .xlsx format (the newer Excel format).
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Attendance");

            // A bold style for the header cells.
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            // Row 0: the column titles.
            Row header = sheet.createRow(0);
            for (int column = 0; column < COLUMNS.length; column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(COLUMNS[column]);
                cell.setCellStyle(headerStyle);
            }

            // One row per student, starting at row 1 (just under the header).
            for (int i = 0; i < students.size(); i++) {
                StudentRecord student = students.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(student.getRollNumber());
                row.createCell(1).setCellValue(student.getName());
                row.createCell(2).setCellValue(student.getDivision());
                row.createCell(3).setCellValue(student.getPresentDays());
                row.createCell(4).setCellValue(student.getAbsentDays());
                row.createCell(5).setCellValue(student.attendancePercent());
            }

            // Column widths are in 1/256ths of a character, so 14 * 256 is about 14 characters wide.
            sheet.setColumnWidth(0, 14 * 256);
            sheet.setColumnWidth(1, 24 * 256);
            sheet.setColumnWidth(2, 14 * 256);
            sheet.setColumnWidth(3, 16 * 256);
            sheet.setColumnWidth(4, 16 * 256);
            sheet.setColumnWidth(5, 16 * 256);

            workbook.write(out);
        }
    }
}
