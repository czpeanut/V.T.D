package com.appgarage.dash;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings. Reached by long-pressing the driving screen.
 *
 * The choice is stored rather than detected, because detection cannot tell an unwanted
 * language from an unavailable one. DashView still probes for a CJK glyph, but only to pick
 * the default the first time the app runs: if this Android layer has no Chinese font, English
 * is what comes up, and the stored preference overrides that from then on.
 */
public class SettingsActivity extends Activity implements View.OnClickListener {

    public static final String PREFS = "dash";
    public static final String KEY_LANG = "lang";
    public static final String LANG_ZH = "zh", LANG_EN = "en";

    private static final int BG = 0xFF070B14, CYAN = 0xFF3FD2FF, DIM = 0xFF1B4E66;
    private static final int WHITE = 0xFFEAF6FF, GREY = 0xFF55697C;

    private Button zhBtn, enBtn, backBtn;
    private TextView note;
    private boolean zh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        zh = LANG_ZH.equals(current(this, LANG_ZH));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(28, 22, 28, 22);

        TextView title = new TextView(this);
        title.setText("V.T.D.   設定 / SETTINGS");
        title.setTextSize(24);
        title.setTextColor(WHITE);
        root.addView(title, wrap());

        View rule = new View(this);
        rule.setBackgroundColor(DIM);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 2);
        rp.topMargin = 10;
        rp.bottomMargin = 26;
        root.addView(rule, rp);

        TextView heading = new TextView(this);
        heading.setText("語言  /  LANGUAGE");
        heading.setTextSize(17);
        heading.setTextColor(GREY);
        root.addView(heading, wrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        zhBtn = choice(row, "中文");
        enBtn = choice(row, "English");
        LinearLayout.LayoutParams rowp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowp.topMargin = 14;
        root.addView(row, rowp);

        note = new TextView(this);
        note.setTextSize(14);
        note.setTextColor(GREY);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = 20;
        root.addView(note, np);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1f));

        backBtn = new Button(this);
        backBtn.setTextSize(18);
        backBtn.setOnClickListener(this);
        root.addView(backBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refresh();
    }

    /** the stored language, or the supplied default when nothing has been chosen yet */
    public static String current(Activity a, String fallback) {
        try {
            SharedPreferences sp = a.getSharedPreferences(PREFS, MODE_PRIVATE);
            return sp.getString(KEY_LANG, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public void onClick(View v) {
        if (v == backBtn) { finish(); return; }
        zh = (v == zhBtn);
        try {
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putString(KEY_LANG, zh ? LANG_ZH : LANG_EN);
            e.commit();                        // commit, not apply: the dash re-reads this
        } catch (Throwable ignored) {}         // on resume and must not race the write
        refresh();
    }

    private void refresh() {
        zhBtn.setTextColor(zh ? CYAN : GREY);
        enBtn.setTextColor(zh ? GREY : CYAN);
        zhBtn.setTypeface(null, zh ? Typeface.BOLD : Typeface.NORMAL);
        enBtn.setTypeface(null, zh ? Typeface.NORMAL : Typeface.BOLD);
        note.setText(zh ? "儀表畫面的標籤會使用中文。數字不受影響。"
                        : "Labels on the driving screen use English. Numbers are unaffected.");
        backBtn.setText(zh ? "返回儀表" : "BACK TO DASH");
    }

    private Button choice(LinearLayout parent, String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(21);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = 12;
        parent.addView(btn, lp);
        return btn;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
