package cm.camtel.nextfleet.domain.report;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Rapport tabulaire au format PDF (A4 paysage), écrit sans bibliothèque externe : polices standard Helvetica en
 * WinAnsiEncoding (accents français, guillemets, « œ »), largeurs exactes des glyphes, retour à la ligne dans les
 * cellules, en-tête de tableau répété sur chaque page, ligne de total pour les montants et les litres, pied de page
 * « Page x / n ». Le contenu de chaque page est compressé (FlateDecode).
 */
public final class PdfReport {

    /** En-tête du document : titre, description, lignes de contexte (période, direction, auteur…). */
    public record Meta(String title, String subtitle, List<String> context, String footer) { }

    /** Colonne : libellé, alignement à droite (nombres), total à calculer. */
    public record Column(String label, boolean numeric, boolean total) { }

    private static final float W = 842f, H = 595f, M = 36f;
    private static final float BODY = 7.5f, LEAD = 9.6f, PAD_X = 4.5f, PAD_Y = 3.6f;
    private static final float BOTTOM = M + 22f;
    private static final float[] INK = rgb(0x0A1620), MUTED = rgb(0x56636D), ACCENT = rgb(0x008575),
            HEAD_BG = rgb(0xEDF1F2), ZEBRA = rgb(0xF6F8F9), RULE = rgb(0xDCE2E5), TOTAL_BG = rgb(0xE6F3F1);

    private static final int[] HELVETICA = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 761,
            556, 0, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,
            0, 222, 222, 333, 333, 350, 556, 1000, 333, 1000, 500, 333, 944, 0, 500, 667,
            278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
            400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611,
            667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
            722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
            556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500
    };
    private static final int[] HELVETICA_BOLD = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
            975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
            333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
            611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 761,
            556, 0, 278, 556, 500, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,
            0, 278, 278, 500, 500, 350, 556, 1000, 333, 1000, 556, 333, 944, 0, 500, 667,
            278, 333, 556, 556, 556, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,
            400, 584, 333, 333, 333, 611, 556, 278, 333, 333, 365, 556, 834, 834, 834, 611,
            722, 722, 722, 722, 722, 722, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
            722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
            556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278,
            611, 611, 611, 611, 611, 611, 611, 584, 611, 611, 611, 611, 611, 556, 611, 556
    };

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    private PdfReport() { }

    /**
     * @param rows valeurs déjà mises en forme (texte) ; {@code totals} contient le total de chaque colonne marquée
     *             {@link Column#total()} (ou {@code null}) — il est calculé par l'appelant sur les valeurs exactes.
     */
    public static byte[] render(Meta meta, List<Column> columns, List<List<String>> rows, List<String> totals) {
        Layout l = new Layout(meta, columns, rows, totals);
        return l.write();
    }

    // ------------------------------------------------------------------ mise en page

    private static final class Layout {
        final Meta meta;
        final List<Column> cols;
        final List<List<String>> rows;
        final List<String> totals;
        final float[] widths;
        final List<StringBuilder> pages = new ArrayList<>();
        StringBuilder cs;
        float y;

        Layout(Meta meta, List<Column> cols, List<List<String>> rows, List<String> totals) {
            this.meta = meta;
            this.cols = cols;
            this.rows = rows;
            this.totals = totals;
            this.widths = columnWidths();
        }

        float[] columnWidths() {
            int n = cols.size();
            float avail = W - 2 * M;
            float[] natural = new float[n], min = new float[n];
            for (int c = 0; c < n; c++) {
                float nat = 0, mn = 0;
                for (String word : cols.get(c).label().split("\\s+")) mn = Math.max(mn, width(word, true, BODY));
                nat = Math.max(nat, width(cols.get(c).label(), true, BODY) * 0.6f);
                int sampled = 0;
                for (List<String> r : rows) {
                    String v = cell(r, c);
                    nat = Math.max(nat, width(v, false, BODY));
                    for (String word : v.split("\\s+")) mn = Math.max(mn, Math.min(width(word, false, BODY), 110f));
                    if (++sampled > 2000) break;
                }
                if (totals != null && c < totals.size() && totals.get(c) != null) {
                    nat = Math.max(nat, width(totals.get(c), true, BODY));
                    mn = Math.max(mn, width(totals.get(c), true, BODY));
                }
                natural[c] = Math.min(nat, 200f) + 2 * PAD_X;
                min[c] = Math.min(Math.max(mn, 18f), 120f) + 2 * PAD_X;
                natural[c] = Math.max(natural[c], min[c]);
            }
            float sumNat = 0, sumMin = 0;
            for (int c = 0; c < n; c++) { sumNat += natural[c]; sumMin += min[c]; }
            float[] w = new float[n];
            if (sumNat <= avail) {
                for (int c = 0; c < n; c++) w[c] = natural[c] * avail / sumNat;
            } else if (sumMin <= avail) {
                float extra = avail - sumMin, want = sumNat - sumMin;
                for (int c = 0; c < n; c++) w[c] = min[c] + (want <= 0 ? 0 : extra * (natural[c] - min[c]) / want);
            } else {
                for (int c = 0; c < n; c++) w[c] = min[c] * avail / sumMin;
            }
            return w;
        }

        byte[] write() {
            newPage(true);
            tableHeader();
            if (rows.isEmpty()) {
                List<String> empty = new ArrayList<>();
                for (int c = 0; c < cols.size(); c++) empty.add(c == 0 ? "Aucune donnée pour cette période et ce périmètre." : "");
                // ligne unique qui couvre toute la largeur
                ensure(LEAD + 2 * PAD_Y);
                text(M + PAD_X, y - PAD_Y - BODY, empty.get(0), false, BODY, MUTED);
                y -= LEAD + 2 * PAD_Y;
            }
            int i = 0;
            for (List<String> r : rows) {
                List<List<String>> lines = wrapRow(r, false);
                float h = rowHeight(lines);
                if (y - h < BOTTOM) {
                    newPage(false);
                    tableHeader();
                }
                if (i % 2 == 1) fill(M, y - h, W - 2 * M, h, ZEBRA);
                drawRow(lines, false, INK);
                hline(y - h, RULE, 0.4f);
                y -= h;
                i++;
            }
            if (totals != null && !rows.isEmpty() && totals.stream().anyMatch(t -> t != null)) {
                List<String> t = new ArrayList<>();
                for (int c = 0; c < cols.size(); c++) {
                    String v = c < totals.size() ? totals.get(c) : null;
                    t.add(v != null ? v : c == 0 ? "Total" : "");
                }
                List<List<String>> lines = wrapRow(t, true);
                float h = rowHeight(lines);
                if (y - h < BOTTOM) {
                    newPage(false);
                    tableHeader();
                }
                fill(M, y - h, W - 2 * M, h, TOTAL_BG);
                hline(y, ACCENT, 0.8f);
                drawRow(lines, true, INK);
                y -= h;
            }
            return serialize();
        }

        void newPage(boolean first) {
            cs = new StringBuilder();
            pages.add(cs);
            fill(0, H - 5, W, 5, ACCENT);
            if (first) {
                text(M, H - M - 4, "NEXFLEET  ·  CAMTEL", true, 7.5f, ACCENT);
                text(M, H - M - 26, meta.title(), true, 18f, INK);
                float yy = H - M - 42;
                if (meta.subtitle() != null && !meta.subtitle().isBlank()) {
                    for (String line : wrap(meta.subtitle(), false, 9f, W - 2 * M)) {
                        text(M, yy, line, false, 9f, MUTED);
                        yy -= 12;
                    }
                }
                yy -= 2;
                for (String c : meta.context()) {
                    text(M, yy, c, false, 8f, INK);
                    yy -= 11;
                }
                y = yy - 8;
            } else {
                text(M, H - M - 6, meta.title() + " (suite)", true, 10f, INK);
                y = H - M - 20;
            }
        }

        void tableHeader() {
            List<String> labels = cols.stream().map(Column::label).toList();
            List<List<String>> lines = wrapRow(labels, true);
            float h = rowHeight(lines);
            fill(M, y - h, W - 2 * M, h, HEAD_BG);
            drawRow(lines, true, INK);
            hline(y - h, MUTED, 0.6f);
            y -= h;
        }

        void ensure(float h) {
            if (y - h < BOTTOM) {
                newPage(false);
                tableHeader();
            }
        }

        List<List<String>> wrapRow(List<String> r, boolean bold) {
            List<List<String>> out = new ArrayList<>();
            for (int c = 0; c < cols.size(); c++) out.add(wrap(cell(r, c), bold, BODY, widths[c] - 2 * PAD_X));
            return out;
        }

        static float rowHeight(List<List<String>> lines) {
            int max = 1;
            for (List<String> l : lines) max = Math.max(max, l.size());
            return max * LEAD + 2 * PAD_Y - (LEAD - BODY) + 1.5f;
        }

        void drawRow(List<List<String>> lines, boolean bold, float[] color) {
            float x = M;
            for (int c = 0; c < cols.size(); c++) {
                float ty = y - PAD_Y - BODY + 0.8f;
                for (String line : lines.get(c)) {
                    float tx = cols.get(c).numeric() ? x + widths[c] - PAD_X - width(line, bold, BODY) : x + PAD_X;
                    text(tx, ty, line, bold, BODY, color);
                    ty -= LEAD;
                }
                x += widths[c];
            }
        }

        void fill(float x, float yy, float w, float h, float[] c) {
            cs.append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" rg ")
                    .append(f(x)).append(' ').append(f(yy)).append(' ').append(f(w)).append(' ').append(f(h)).append(" re f\n");
        }

        void hline(float yy, float[] c, float width) {
            cs.append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" RG ").append(f(width)).append(" w ")
                    .append(f(M)).append(' ').append(f(yy)).append(" m ").append(f(W - M)).append(' ').append(f(yy)).append(" l S\n");
        }

        void text(float x, float yy, String s, boolean bold, float size, float[] c) {
            if (s == null || s.isEmpty()) return;
            cs.append("BT ").append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" rg /")
                    .append(bold ? "F2 " : "F1 ").append(f(size)).append(" Tf 1 0 0 1 ").append(f(x)).append(' ').append(f(yy))
                    .append(" Tm (").append(literal(s)).append(") Tj ET\n");
        }

        byte[] serialize() {
            int n = pages.size();
            String stamp = meta.footer() == null ? "" : meta.footer();
            for (int p = 0; p < n; p++) {
                StringBuilder s = pages.get(p);
                cs = s;
                text(M, M, stamp, false, 7f, MUTED);
                String pg = "Page " + (p + 1) + " / " + n;
                text(W - M - width(pg, false, 7f), M, pg, false, 7f, MUTED);
            }
            Writer w = new Writer();
            int pagesObj = 2, fontR = 3, fontB = 4, info = 5;
            int firstPage = 6;
            w.obj(1, "<< /Type /Catalog /Pages 2 0 R /ViewerPreferences << /DisplayDocTitle true >> >>");
            StringBuilder kids = new StringBuilder();
            for (int p = 0; p < n; p++) kids.append(firstPage + 2 * p).append(" 0 R ");
            w.obj(pagesObj, "<< /Type /Pages /Count " + n + " /Kids [" + kids + "] >>");
            w.obj(fontR, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
            w.obj(fontB, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
            w.obj(info, "<< /Title " + utf16(meta.title()) + " /Producer (NexFleet) /Creator (NexFleet CAMTEL) >>");
            for (int p = 0; p < n; p++) {
                int pageObj = firstPage + 2 * p, contentObj = pageObj + 1;
                w.obj(pageObj, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + f(W) + " " + f(H) + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                        + contentObj + " 0 R >>");
                w.stream(contentObj, pages.get(p).toString());
            }
            return w.finish(1, info);
        }
    }

    // ------------------------------------------------------------------ texte

    static String cell(List<String> r, int c) {
        String v = c < r.size() ? r.get(c) : null;
        return v == null ? "" : v.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
    }

    /** Coupe un texte aux espaces pour tenir dans {@code max} points ; un mot trop long est coupé à la lettre. */
    static List<String> wrap(String s, boolean bold, float size, float max) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : s.strip().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, bold, size) <= max) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (width(rest, bold, size) > max && rest.length() > 1) {
                int k = 1;
                while (k < rest.length() && width(rest.substring(0, k + 1), bold, size) <= max) k++;
                out.add(rest.substring(0, k));
                rest = rest.substring(k);
            }
            line.append(rest);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    static float width(String s, boolean bold, float size) {
        if (s == null || s.isEmpty()) return 0;
        int[] t = bold ? HELVETICA_BOLD : HELVETICA;
        long sum = 0;
        for (byte b : encode(s)) sum += t[b & 0xFF];
        return sum * size / 1000f;
    }

    /** Texte → octets WinAnsi ; les caractères absents sont remplacés par un équivalent lisible ou « ? ». */
    static byte[] encode(String s) {
        String v = s.replace(' ', ' ').replace(' ', ' ').replace("≤", "<=").replace("≥", ">=")
                .replace('−', '-').replace('‐', '-').replace('‑', '-').replace("✓", "oui").replace("✕", "x");
        CharsetEncoder enc = WIN_ANSI.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(new byte[]{'?'});
        try {
            ByteBuffer bb = enc.encode(CharBuffer.wrap(v));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (java.nio.charset.CharacterCodingException e) {
            return v.getBytes(StandardCharsets.US_ASCII);
        }
    }

    /** Chaîne littérale PDF : octets WinAnsi, parenthèses et barres obliques inverses échappées. */
    static String literal(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : encode(s)) {
            int c = x & 0xFF;
            if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
            else if (c < 32) b.append(' ');
            else b.append((char) c);    // écrit ensuite en ISO-8859-1 : un caractère = un octet
        }
        return b.toString();
    }

    static String utf16(String s) {
        StringBuilder b = new StringBuilder("<FEFF");
        for (char c : (s == null ? "" : s).toCharArray()) b.append(String.format("%04X", (int) c));
        return b.append('>').toString();
    }

    static String f(float v) {
        if (Math.abs(v - Math.round(v)) < 0.005f) return Integer.toString(Math.round(v));
        return new BigDecimal(Float.toString(v)).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static float[] rgb(int hex) {
        return new float[]{((hex >> 16) & 0xFF) / 255f, ((hex >> 8) & 0xFF) / 255f, (hex & 0xFF) / 255f};
    }

    // ------------------------------------------------------------------ écriture du fichier

    private static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final java.util.TreeMap<Integer, Integer> offsets = new java.util.TreeMap<>();

        Writer() {
            raw("%PDF-1.4\n");
            out.writeBytes(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
        }

        void raw(String s) {
            out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        void obj(int id, String body) {
            offsets.put(id, out.size());
            raw(id + " 0 obj\n" + body + "\nendobj\n");
        }

        void stream(int id, String content) {
            byte[] data = content.getBytes(StandardCharsets.ISO_8859_1);
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!d.finished()) z.write(buf, 0, d.deflate(buf));
            d.end();
            byte[] packed = z.toByteArray();
            offsets.put(id, out.size());
            raw(id + " 0 obj\n<< /Length " + packed.length + " /Filter /FlateDecode >>\nstream\n");
            out.writeBytes(packed);
            raw("\nendstream\nendobj\n");
        }

        byte[] finish(int root, int info) {
            int size = offsets.lastKey() + 1;
            int xref = out.size();
            StringBuilder x = new StringBuilder("xref\n0 ").append(size).append("\n0000000000 65535 f \n");
            for (int i = 1; i < size; i++) {
                Integer o = offsets.get(i);
                x.append(o == null ? "0000000000 65535 f \n" : String.format("%010d 00000 n \n", o));
            }
            x.append("trailer\n<< /Size ").append(size).append(" /Root ").append(root).append(" 0 R /Info ").append(info)
                    .append(" 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
            raw(x.toString());
            return out.toByteArray();
        }
    }
}
