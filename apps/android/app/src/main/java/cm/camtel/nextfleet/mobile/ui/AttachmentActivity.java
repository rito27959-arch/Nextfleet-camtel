package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import cm.camtel.nextfleet.mobile.NexFleetApp;

/**
 * Visionneuse de pièce jointe (photo ou PDF) dans l'application : le fichier est relu par l'API (droits revérifiés),
 * gardé dans le cache privé le temps de l'affichage, puis effacé. Aucune application tierce n'y accède.
 */
public final class AttachmentActivity extends Activity {

    private File temp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (NexFleetApp.get().session() == null) {
            finish();
            return;
        }
        String id = getIntent().getStringExtra("id");
        String name = getIntent().getStringExtra("name");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        LinearLayout bar = Ui.row(this, 8);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView done = Ui.text(this, "Terminé", 17, 0xFFFFFFFF, NexFleetApp.get().semibold());
        done.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        done.setBackground(Ui.rounded(this, 0x33FFFFFF, 999));
        done.setOnClickListener(v -> finish());
        TextView t = Ui.text(this, name == null ? "Pièce jointe" : name, 15, 0xFFFFFFFF, NexFleetApp.get().regular());
        t.setGravity(Gravity.CENTER);
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(done);
        root.addView(bar);
        Ui.applyInsets(bar, true, false);
        LinearLayout pages = Ui.column(this, 8);
        pages.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 24));
        TextView status = Ui.text(this, "Chargement…", 15, 0xFFBFC6CC, NexFleetApp.get().regular());
        status.setGravity(Gravity.CENTER);
        pages.addView(status);
        ScrollView sv = new ScrollView(this);
        sv.addView(pages);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.applyInsets(sv, false, true);
        setContentView(root);

        int width = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 16);
        Ui.async(() -> NexFleetApp.get().repository().download(id), bytes -> {
            pages.removeAllViews();
            try {
                if (bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                    temp = File.createTempFile("piece", ".pdf", getCacheDir());
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        out.write(bytes);
                    }
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
                         PdfRenderer pdf = new PdfRenderer(fd)) {
                        int count = Math.min(pdf.getPageCount(), 20);
                        for (int i = 0; i < count; i++) {
                            try (PdfRenderer.Page p = pdf.openPage(i)) {
                                int h = (int) ((float) p.getHeight() / p.getWidth() * width);
                                Bitmap b = Bitmap.createBitmap(width, Math.max(h, 1), Bitmap.Config.ARGB_8888);
                                b.eraseColor(0xFFFFFFFF);
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                pages.addView(image(b));
                            }
                        }
                    }
                } else {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    o.inSampleSize = 1;
                    while (o.outWidth / o.inSampleSize > width * 2) o.inSampleSize *= 2;
                    o.inJustDecodeBounds = false;
                    Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                    if (b == null) throw new IOException("format");
                    pages.addView(image(b));
                }
            } catch (IOException | RuntimeException e) {
                pages.addView(Ui.text(this, "Affichage impossible de ce fichier.", 15, 0xFFBFC6CC, NexFleetApp.get().regular()));
            }
        }, e -> {
            pages.removeAllViews();
            pages.addView(Ui.text(this, e.getMessage(), 15, 0xFFFF8A80, NexFleetApp.get().regular()));
        });
    }

    private ImageView image(Bitmap b) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        iv.setContentDescription("Page de la pièce jointe");
        return iv;
    }

    @Override
    protected void onDestroy() {
        if (temp != null && !temp.delete()) temp.deleteOnExit();
        super.onDestroy();
    }
}
