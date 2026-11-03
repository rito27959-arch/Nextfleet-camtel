package cm.camtel.nextfleet.domain.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportTest {

    private static final List<PdfReport.Column> COLS = List.of(new PdfReport.Column("Véhicule", false, false),
            new PdfReport.Column("Éléments remplacés", false, false), new PdfReport.Column("Coût (XAF)", true, true));

    @Test
    void structure_valide_pagination_et_xref_exacte() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) rows.add(List.of("LT " + (1000 + i) + " CE", "Filtre à huile, liquide de refroidissement", "45 000"));
        byte[] pdf = PdfReport.render(new PdfReport.Meta("Vidanges", "Description", List.of("Période : du 01/10/2026 au 30/10/2026"), "pied"),
                COLS, rows, Arrays.asList(null, null, "5 400 000"));
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("%PDF-1.4"));
        assertTrue(s.endsWith("%%EOF\n"));
        Matcher count = Pattern.compile("/Count (\\d+)").matcher(s);
        assertTrue(count.find());
        int pages = Integer.parseInt(count.group(1));
        assertTrue(pages >= 3, "120 lignes tiennent sur plusieurs pages : " + pages);
        // chaque entrée de la table xref pointe exactement sur « n 0 obj »
        int xref = Integer.parseInt(s.substring(s.lastIndexOf("startxref") + 10).strip().split("\\s+")[0]);
        String[] lines = s.substring(xref).split("\n");
        int size = Integer.parseInt(lines[1].split(" ")[1]);
        for (int i = 1; i < size; i++) {
            int off = Integer.parseInt(lines[2 + i].substring(0, 10));
            assertTrue(s.startsWith(i + " 0 obj", off), "objet " + i);
        }
        String text = allContent(s);
        assertTrue(text.contains("(Page 1 / " + pages + ")"));
        assertTrue(text.contains("(Total)") && text.contains("(5 400 000)"));
        assertTrue(text.contains("(Vidanges \\(suite\\))"), "titre répété et parenthèses échappées");
        assertTrue(text.contains("(Filtre \u00E0 huile") && text.contains("\u00C9l\u00E9ments"), "accents encodés en WinAnsi");
    }

    @Test
    void rapport_vide_une_page_avec_message() throws Exception {
        byte[] pdf = PdfReport.render(new PdfReport.Meta("Vide", null, List.of(), null), COLS, List.of(), null);
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(s.contains("/Count 1"));
        assertTrue(allContent(s).contains("Aucune donnée"));
    }

    @Test
    void coupure_des_mots_et_largeurs() {
        assertEquals(List.of(""), PdfReport.wrap("", false, 8, 50));
        List<String> l = PdfReport.wrap("Filtre à huile, filtre à carburant, liquide de refroidissement", false, 7.5f, 80);
        assertTrue(l.size() >= 3);
        for (String line : l) assertTrue(PdfReport.width(line, false, 7.5f) <= 80.01f, line);
        List<String> hard = PdfReport.wrap("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", true, 8, 40);
        assertTrue(hard.size() > 1);
        assertEquals(PdfReport.width("ii", false, 10), 2 * PdfReport.width("i", false, 10), 0.001);
        assertEquals("?", new String(PdfReport.encode("中"), StandardCharsets.ISO_8859_1));
    }

    private static String allContent(String pdf) throws Exception {
        StringBuilder out = new StringBuilder();
        byte[] raw = pdf.getBytes(StandardCharsets.ISO_8859_1);
        int from = 0;
        while ((from = pdf.indexOf(">>\nstream\n", from)) >= 0) {
            int start = from + 10;
            int end = pdf.indexOf("\nendstream", start);
            Inflater inf = new Inflater();
            inf.setInput(raw, start, end - start);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) break;
                b.write(buf, 0, n);
            }
            out.append(b.toString(StandardCharsets.ISO_8859_1));
            from = end;
        }
        return out.toString();
    }
}
