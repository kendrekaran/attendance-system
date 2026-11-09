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
 */
public class ExcelExporter {
    public static final String[] COLUMNS = {
            "Roll No",
            "Name",
            "Division",
            "Present Days",
            "Absent Days",
            "Attendance %"
    };

    public static void write(Path file, List<StudentRecord> students) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            write(out, students);
        }
    }

    /** Writes the workbook to {@code out}. The caller closes the stream. */
    public static void write(OutputStream out, List<StudentRecord> students) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Attendance");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int column = 0; column < COLUMNS.length; column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(COLUMNS[column]);
                cell.setCellStyle(headerStyle);
            }

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
