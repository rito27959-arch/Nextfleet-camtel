package cm.camtel.nextfleet.mobile.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Models;
import cm.camtel.nextfleet.mobile.R;
import cm.camtel.nextfleet.mobile.data.Models.ChatLine;
import cm.camtel.nextfleet.mobile.data.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fil de conversation (iMessage) : bulles, séparateurs horaires, état discret sous la dernière bulle envoyée.
 * « Envoyé » n'apparaît qu'après l'accusé du serveur ; hors réseau, le message reste « Non envoyé » et sa réémission
 * réutilise le même identifiant client (aucun doublon). Rafraîchissement toutes les 5 s.
 */
public final class ThreadActivity extends Activity {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Africa/Douala"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, String[]> pending = new LinkedHashMap<>();   // clientId → {texte, "1" si échec}
    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private ImageButton send;
    private String conversationId;
    private List<ChatLine> lines = List.of();
    private static final int PICK_FILE = 52;
    private byte[] attachment;
    private String attachmentName;
    private TextView attachChip;
    private AutoCloseable realtime;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (NexFleetApp.get().session() == null) {
            finish();
            return;
        }
        conversationId = getIntent().getStringExtra("id");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.backdrop());

        LinearLayout header = Ui.row(this, 8);
        header.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 10));
        // iOS 26 : bouton retour en capsule de verre, titre centré ; pas de fond de barre
        TextView back = Ui.text(this, "‹", 26, Ui.TEXT, NexFleetApp.get().regular());
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.glass(this, 999));
        back.setElevation(Ui.dp(this, 4));
        back.setContentDescription("Retour aux messages");
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = Ui.headline(this, getIntent().getStringExtra("title"));
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 44), 1));
        root.addView(header);
        Ui.applyInsets(header, true, false);

        list = Ui.column(this, 3);
        list.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout composer = Ui.row(this, 10);
        composer.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        composer.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 12));
        input = new EditText(this);
        input.setHint("Message");
        input.setTypeface(NexFleetApp.get().regular());
        input.setTextSize(16);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.TERTIARY);
        input.setMaxLines(5);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(Ui.glass(this, 22));
        input.setElevation(Ui.dp(this, 3));
        input.setMinHeight(Ui.dp(this, 44));
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        input.setOnEditorActionListener((v, id, e) -> {
            submit();
            return true;
        });
        if (!NexFleetApp.get().repository().isDemo()) {
            ImageButton attach = new ImageButton(this);
            attach.setImageResource(R.drawable.ic_attach);
            attach.setBackground(Ui.glass(this, 999));
            attach.setContentDescription("Joindre une photo ou un PDF");
            attach.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp", "application/pdf"});
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Joindre une photo ou un PDF"), PICK_FILE);
            });
            composer.addView(attach, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        }
        composer.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        send = new ImageButton(this);
        send.setImageResource(R.drawable.ic_send);
        send.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        send.setBackground(Ui.rounded(this, Ui.ACCENT, 999));
        send.setContentDescription("Envoyer");
        send.setOnClickListener(v -> submit());
        composer.addView(send, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                refreshSend();
            }
        });
        send.setEnabled(false);
        send.setBackground(Ui.rounded(this, 0xFFC7C7CC, 999));
        attachChip = Ui.text(this, "", 13, Ui.TEXT, NexFleetApp.get().regular());
        attachChip.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        attachChip.setBackground(Ui.rounded(this, Ui.FILL, 999));
        attachChip.setVisibility(View.GONE);
        attachChip.setContentDescription("Retirer la pièce jointe");
        attachChip.setOnClickListener(v -> {
            attachment = null;
            attachChip.setVisibility(View.GONE);
            refreshSend();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = Ui.dp(this, 14);
        root.addView(attachChip, cp);
        root.addView(composer);
        Ui.applyInsets(composer, false, true);
        setContentView(root);
    }

    private void refreshSend() {
        boolean on = input.getText().toString().trim().length() > 0 || attachment != null;
        send.setEnabled(on);
        send.setBackground(Ui.rounded(this, on ? Ui.ACCENT : 0xFFC7C7CC, 999));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("illisible");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 10 * 1024 * 1024) throw new IllegalArgumentException("Fichier trop volumineux (10 Mo maximum).");
            }
            attachment = out.toByteArray();
            attachmentName = "piece";
            try (android.database.Cursor c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) attachmentName = c.getString(0);
            }
            attachChip.setText("📎 " + attachmentName + "  ✕");
            attachChip.setVisibility(View.VISIBLE);
            refreshSend();
        } catch (java.io.IOException | IllegalArgumentException e) {
            attachment = null;
            Ui.alert(this, "Pièce jointe", e instanceof IllegalArgumentException ? e.getMessage() : "Fichier illisible.", null, "OK", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload(true);
        // temps réel : le serveur signale un nouveau message ; le fil est relu par l'API (droits revérifiés)
        realtime = NexFleetApp.get().repository().messagesLive(conv -> {
            if (conv.equals(conversationId)) reload(true);
        });
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        if (realtime != null) {
            try {
                realtime.close();
            } catch (Exception ignored) {
                // déjà fermé
            }
            realtime = null;
        }
        super.onPause();
    }

    private void reload(boolean markRead) {
        Repository repo = NexFleetApp.get().repository();
        Ui.async(() -> {
            List<ChatLine> l = repo.messages(conversationId);
            if (markRead) repo.markRead(conversationId);
            return l;
        }, l -> {
            lines = l;
            render(null);
        }, e -> render(e.getMessage()));
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> reload(false), 5_000);
    }

    private void render(String error) {
        list.removeAllViews();
        if (error != null) {
            TextView t = Ui.text(this, error, 13, Ui.RED, NexFleetApp.get().regular());
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            t.setBackground(Ui.rounded(this, 0x1FFF383C, 12));
            list.addView(t);
        }
        Instant prev = null;
        String prevSender = null;
        int lastMine = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).mine) lastMine = i;
        for (int i = 0; i < lines.size(); i++) {
            ChatLine l = lines.get(i);
            if (prev == null || Duration.between(prev, l.at).toMinutes() >= 30) {
                TextView sep = Ui.text(this, HM.format(l.at), 11, Ui.MUTED, NexFleetApp.get().semibold());
                sep.setGravity(Gravity.CENTER);
                sep.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
                list.addView(sep, Ui.matchWrap());
                prevSender = null;
            }
            if (!l.mine && !l.senderName.equals(prevSender)) {
                TextView who = Ui.muted(this, l.senderName, 11);
                who.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), 0, 0);
                list.addView(who);
            }
            list.addView(bubble(l.body, l.mine, false, false));
            for (Models.Attachment f : l.attachments) {
                TextView chip = Ui.text(this, "📎 " + f.name + " · " + Math.max(1, f.sizeBytes / 1024) + " Ko", 13,
                        l.mine ? Ui.ACCENT_TEXT : Ui.TEXT, NexFleetApp.get().semibold());
                chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
                chip.setBackground(Ui.rounded(this, Ui.FILL, 999));
                chip.setContentDescription("Ouvrir la pièce jointe " + f.name);
                chip.setOnClickListener(v -> startActivity(new Intent(this, AttachmentActivity.class).putExtra("id", f.id).putExtra("name", f.name)));
                LinearLayout row = new LinearLayout(this);
                row.setGravity(l.mine ? Gravity.END : Gravity.START);
                row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
                row.addView(chip);
                list.addView(row);
            }
            if (i == lastMine && l.state != null) list.addView(meta(l.state.label, false, null));
            prev = l.at;
            prevSender = l.senderName;
        }
        for (Map.Entry<String, String[]> p : pending.entrySet()) {
            boolean failed = "1".equals(p.getValue()[1]);
            list.addView(bubble(p.getValue()[0], true, !failed, failed));
            list.addView(failed ? meta(Chat.DeliveryState.ECHEC.label + " · Toucher pour réessayer", true, () -> deliver(p.getKey()))
                    : meta(Chat.DeliveryState.EN_ATTENTE.label, false, null));
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(String text, boolean mine, boolean pendingState, boolean failed) {
        TextView b = Ui.text(this, text, 15.5f, mine && !failed ? 0xFFFFFFFF : failed ? Ui.RED : Ui.TEXT, NexFleetApp.get().regular());
        b.setPadding(Ui.dp(this, 13), Ui.dp(this, 8), Ui.dp(this, 13), Ui.dp(this, 9));
        b.setBackground(Ui.rounded(this, failed ? 0x1FFF383C : mine ? Ui.ACCENT : 0xFFFFFFFF, 20));
        b.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.72));
        if (pendingState) b.setAlpha(0.6f);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(mine ? Gravity.END : Gravity.START);
        row.addView(b);
        return row;
    }

    private View meta(String text, boolean failed, Runnable onClick) {
        TextView t = Ui.text(this, text, 11, failed ? Ui.RED : Ui.MUTED, NexFleetApp.get().regular());
        t.setGravity(Gravity.END);
        t.setPadding(0, 0, Ui.dp(this, 4), Ui.dp(this, 4));
        if (onClick != null) t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private final Map<String, Object[]> pendingFiles = new LinkedHashMap<>();   // clientId → {octets, nom}

    private void submit() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && attachment == null) return;
        if (text.isEmpty()) text = "Pièce jointe : " + attachmentName;
        String id = UUID.randomUUID().toString();
        pending.put(id, new String[]{attachment == null ? text : text + "\n📎 " + attachmentName, "0", text});
        if (attachment != null) pendingFiles.put(id, new Object[]{attachment, attachmentName});
        attachment = null;
        attachChip.setVisibility(View.GONE);
        input.setText("");
        render(null);
        deliver(id);
    }

    /** Envoi ou réémission : toujours le même identifiant client pour un même message. */
    private void deliver(String clientId) {
        String[] p = pending.get(clientId);
        if (p == null) return;
        p[1] = "0";
        render(null);
        Object[] file = pendingFiles.get(clientId);
        Ui.async(() -> {
            Repository repo = NexFleetApp.get().repository();
            List<String> ids = file == null ? List.of() : List.of(repo.upload("CHAT", null, (String) file[1], (byte[]) file[0]));
            return repo.send(conversationId, clientId, p.length > 2 ? p[2] : p[0], ids);
        }, line -> {
            pending.remove(clientId);
            pendingFiles.remove(clientId);
            reload(false);
        }, e -> {
            boolean refused = e instanceof Repository.RepositoryException
                    && (((Repository.RepositoryException) e).kind == Repository.RepositoryException.Kind.FORBIDDEN
                    || ((Repository.RepositoryException) e).kind == Repository.RepositoryException.Kind.VALIDATION);
            if (refused) {
                pending.remove(clientId);
                pendingFiles.remove(clientId);
                Ui.alert(this, "Message non envoyé", e.getMessage(), null, "OK", null);
            } else {
                p[1] = "1";
            }
            render(null);
        });
    }
}
