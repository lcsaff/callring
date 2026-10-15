package com.greatwall.callring;

final class CsvReport {
    static String cell(String value, boolean forceText) {
        String text = value == null ? "" : value;
        int first=0;while(first<text.length()&&Character.isWhitespace(text.charAt(first)))first++;
        String leading = text.substring(first);
        if (forceText || (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0)
                || text.startsWith("\t") || text.startsWith("\r")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    static String row(String... values) {
        StringBuilder line = new StringBuilder();
        for (String value : values) {
            if (line.length() > 0) line.append(',');
            line.append(cell(value, false));
        }
        return line.append("\r\n").toString();
    }
}
