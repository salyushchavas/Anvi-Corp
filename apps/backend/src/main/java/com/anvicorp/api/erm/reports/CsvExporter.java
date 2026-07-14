package com.anvicorp.api.erm.reports;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ERM Phase 7 — minimal RFC 4180 CSV writer. We don't pull in
 * commons-csv to keep the dependency surface small; the rules we need
 * (quote-wrap when value contains comma / quote / newline; double-up
 * embedded quotes; UTF-8 BOM for Excel compatibility) fit in ~40 lines.
 */
public final class CsvExporter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CRLF = "\r\n";

    private CsvExporter() {}

    public static void writeBom(OutputStream out) throws IOException {
        out.write(UTF8_BOM);
    }

    public static void writeRow(OutputStream out, List<?> cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells.get(i)));
        }
        sb.append(CRLF);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String escape(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        boolean mustQuote = s.indexOf(',') >= 0
                || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0;
        if (!mustQuote) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
