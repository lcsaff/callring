package com.greatwall.callring;

import org.junit.Test;
import static org.junit.Assert.*;

public class CsvReportTest {
    @Test public void formulasAreExportedAsText() {
        assertEquals("\"'=1+1\"", CsvReport.cell("=1+1", false));
        assertEquals("\"' +SUM(A1)\"", CsvReport.cell(" +SUM(A1)", false));
        assertEquals("\"'@name\"", CsvReport.cell("@name", false));
    }
    @Test public void quotedMultilineValuesRemainOneCell() {
        assertEquals("\"研发,\"\"组\"\"\n甲\"", CsvReport.cell("研发,\"组\"\n甲", false));
    }
    @Test public void phoneNumbersKeepTheirTextForm() {
        assertEquals("\"'03123\"", CsvReport.cell("03123", true));
        assertEquals("\"\"", CsvReport.cell(null, false));
    }
}
