package attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelExporterTest {
    @Test
    void writesTheFilteredRowsWithTheRequiredColumns() throws Exception {
        Path file = Files.createTempFile("attendance", ".xlsx");
        try {
            List<StudentRecord> rows = List.of(
                    new StudentRecord(1, "101", "Asha Rao", "A", 2, 1),
                    new StudentRecord(2, "102", "Ben Cole", "A", 0, 0));
            ExcelExporter.write(file, rows);

            try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(file))) {
                Sheet sheet = workbook.getSheet("Attendance");
                Row header = sheet.getRow(0);
                for (int column = 0; column < ExcelExporter.COLUMNS.length; column++) {
                    assertEquals(ExcelExporter.COLUMNS[column], header.getCell(column).getStringCellValue());
                }

                Row asha = sheet.getRow(1);
                assertEquals("101", asha.getCell(0).getStringCellValue());
                assertEquals("Asha Rao", asha.getCell(1).getStringCellValue());
                assertEquals("A", asha.getCell(2).getStringCellValue());
                assertEquals(2, (int) asha.getCell(3).getNumericCellValue());
                assertEquals(1, (int) asha.getCell(4).getNumericCellValue());
                assertEquals("66.7", asha.getCell(5).getStringCellValue());

                Row ben = sheet.getRow(2);
                assertEquals("102", ben.getCell(0).getStringCellValue());
                assertEquals(0, (int) ben.getCell(3).getNumericCellValue());
                assertEquals(0, (int) ben.getCell(4).getNumericCellValue());
                assertEquals("\u2014", ben.getCell(5).getStringCellValue());
                assertEquals(2, sheet.getLastRowNum());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
