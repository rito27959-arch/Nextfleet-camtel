package cm.camtel.nextfleet.mobile.ui;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import cm.camtel.nextfleet.domain.chat.Chat;
import cm.camtel.nextfleet.mobile.NexFleetApp;
import cm.camtel.nextfleet.mobile.data.Models.Conversation;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Conversations dont l'utilisateur est participant (mission, atelier, directes) ; non lus en pastille bleue. */
final class MessagesPage implements Page {

    private static final ZoneId DLA = ZoneId.of("Africa/Douala");
    private final MainActivity a;
    private final ScrollView scroll;
    private final LinearLayout col;
    private AutoCloseable realtime;

    MessagesPage(MainActivity a) {
        this.a = a;
        col = Ui.column(a, 0);
        col.setPadding(Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, 16), Ui.dp(a, Ui.TAB_CLEARANCE));
        scroll = new ScrollView(a);
        scroll.addView(col);
        Ui.applyInsets(scroll, true, false);
    }

    @Override
    public String title() {
        return "Messages";
    }

    @Override
    public int icon() {
        return cm.camtel.nextfleet.mobile.R.drawable.ic_tab_messages;
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public void onShow() {
        if (realtime == null) realtime = NexFleetApp.get().repository().messagesLive(conv -> reload());
        reload();
    }

    @Override
    public void onHide() {
        if (realtime != null) {
            try {
                realtime.close();
            } catch (Exception ignored) {
                // déjà fermé
            }
            realtime = null;
        }
    }

    private void reload() {
        Ui.async(() -> NexFleetApp.get().repository().conversations(), this::render, e -> {
            col.removeAllViews();
            col.addView(Ui.largeTitle(a, "Messages"));
            col.addView(Ui.text(a, e.getMessage(), 14, Ui.RED, NexFleetApp.get().regular()));
        });
    }

    private void render(List<Conversation> list) {
        col.removeAllViews();
        TextView t = Ui.largeTitle(a, "Messages");
        LinearLayout head = Ui.row(a, 8);
        head.setPadding(Ui.dp(a, 4), 0, 0, Ui.dp(a, 12));
        head.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!NexFleetApp.get().repository().isDemo()) {
            TextView plus = Ui.text(a, "+", 26, 0xFFFFFFFF, NexFleetApp.get().semibold());
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.rounded(a, Ui.ACCENT, 999));
            plus.setContentDescription("Nouvelle conversation");
            plus.setOnClickListener(v -> compose());
            head.addView(plus, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        }
        col.addView(head);
        LinearLayout group = Ui.group(a);
        for (int i = 0; i < list.size(); i++) {
            Conversation c = list.get(i);
            if (i > 0) {
                View sep = new View(a);
                sep.setBackgroundColor(Ui.SEPARATOR);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.leftMargin = Ui.dp(a, 72);
                group.addView(sep, lp);
            }
            group.addView(row(c));
        }
        if (list.isEmpty()) group.addView(Ui.muted(a, "Aucune conversation.", 14));
        col.addView(group);
    }

    private View row(Conversation c) {
        LinearLayout r = Ui.row(a, 12);
        r.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
        TextView avatar = Ui.text(a, c.kind == Chat.Kind.MISSION ? "M" : c.kind == Chat.Kind.SERVICE ? "A" : initials(c.title), 16,
                0xFFFFFFFF, NexFleetApp.get().semibold());
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.rounded(a, c.kind == Chat.Kind.MISSION ? Ui.BRAND : c.kind == Chat.Kind.SERVICE ? 0xFFFF8D28 : 0xFF8E9AA6,
                999));
        r.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        LinearLayout text = Ui.column(a, 3);
        LinearLayout top = Ui.row(a, 6);
        top.addView(Ui.headline(a, c.title), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(Ui.muted(a, c.lastAt == null ? "" : when(c), 12));
        text.addView(top);
        LinearLayout bottom = Ui.row(a, 8);
        TextView preview = Ui.muted(a, c.lastBody == null ? c.subtitle : c.lastBody, 14);
        preview.setMaxLines(2);
        bottom.addView(preview, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (c.unread > 0) {
            TextView badge = Ui.text(a, String.valueOf(c.unread), 11, 0xFFFFFFFF, NexFleetApp.get().semibold());
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(Ui.dp(a, 20));
            badge.setPadding(Ui.dp(a, 6), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2));
            badge.setBackground(Ui.rounded(a, 0xFF0088FF, 10));
            bottom.addView(badge);
        }
        text.addView(bottom);
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14000000), null,
                new android.graphics.drawable.ColorDrawable(0xFF000000)));
        r.setOnClickListener(v -> a.startActivity(new Intent(a, ThreadActivity.class).putExtra("id", c.id).putExtra("title", c.title)));
        r.setContentDescription(c.title + (c.unread > 0 ? ", " + c.unread + " non lus" : ""));
        return r;
    }

    /** Nouvelle conversation : une personne (conversation réutilisée si elle existe) ou un groupe nommé. */
    private void compose() {
        Ui.async(() -> NexFleetApp.get().repository().contacts(), contacts -> {
            if (contacts.isEmpty()) {
                Ui.alert(a, "Aucun contact", "Personne d'autre n'a accès à la messagerie dans votre périmètre.", null, "OK", null);
                return;
            }
            LinearLayout box = Ui.column(a, 4);
            box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
            java.util.List<android.widget.CheckBox> checks = new java.util.ArrayList<>();
            for (cm.camtel.nextfleet.mobile.data.Models.Contact c : contacts) {
                android.widget.CheckBox cb = new android.widget.CheckBox(a);
                cb.setText(c.displayName + " · " + c.role);
                cb.setTag(c.userId);
                cb.setTypeface(NexFleetApp.get().regular());
                checks.add(cb);
                box.addView(cb);
            }
            android.widget.EditText title = new android.widget.EditText(a);
            title.setHint("Nom du groupe (plusieurs personnes)");
            box.addView(title);
            ScrollView sv = new ScrollView(a);
            sv.addView(box);
            new android.app.AlertDialog.Builder(a).setTitle("Nouvelle conversation").setView(sv)
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Ouvrir", (d, w) -> {
                        java.util.List<String> ids = new java.util.ArrayList<>();
                        for (android.widget.CheckBox cb : checks) if (cb.isChecked()) ids.add((String) cb.getTag());
                        if (ids.isEmpty()) return;
                        String name = title.getText().toString().trim();
                        Ui.async(() -> NexFleetApp.get().repository().openConversation(ids, ids.size() > 1 ? name : null),
                                conv -> a.startActivity(new Intent(a, ThreadActivity.class).putExtra("id", conv.id).putExtra("title", conv.title)),
                                e -> Ui.alert(a, "Conversation impossible", e.getMessage(), null, "OK", null));
                    }).show();
        }, e -> Ui.alert(a, "Contacts indisponibles", e.getMessage(), null, "OK", null));
    }

    private static String when(Conversation c) {
        var z = c.lastAt.atZone(DLA);
        LocalDate today = LocalDate.now(DLA);
        if (z.toLocalDate().equals(today)) return z.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (z.toLocalDate().equals(today.minusDays(1))) return "Hier";
        return z.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE));
    }

    static String initials(String n) {
        String[] p = n.trim().split("\\s+");
        String s = (p.length > 0 && !p[0].isEmpty() ? p[0].substring(0, 1) : "") + (p.length > 1 ? p[p.length - 1].substring(0, 1) : "");
        return s.toUpperCase(Locale.FRANCE);
    }
}
