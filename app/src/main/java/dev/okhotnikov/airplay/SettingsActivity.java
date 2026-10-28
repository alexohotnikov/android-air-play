package dev.okhotnikov.airplay;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Remote-friendly settings: the name in Screen Mirroring and background mode. */
public final class SettingsActivity extends Activity {

    private EditText name;
    private Switch background;
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0B0B12);
        int pad = (int) (64 * dp);
        col.setPadding(pad * 2, pad, pad * 2, pad);

        col.addView(label(getString(R.string.settings), 34, 0xFFFFFFFF, "sans-serif-light", 0));

        col.addView(label(getString(R.string.settings_name), 16, 0x99FFFFFF, "sans-serif", 36));
        name = new EditText(this);
        name.setText(Prefs.name(this));
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setTextSize(22);
        name.setTextColor(0xFFFFFFFF);
        name.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
        name.setBackground(pill());
        name.setOnEditorActionListener((v, action, e) -> {
            saveName();
            return false;
        });
        col.addView(name, row(8));

        TextView reset = button(getString(R.string.settings_reset, Prefs.factoryName(this)));
        reset.setOnClickListener(v -> {
            name.setText(Prefs.factoryName(this));
            saveName();
        });
        col.addView(reset, row(12));

        background = new Switch(this);
        background.setText(R.string.settings_background);
        background.setTextSize(22);
        background.setTextColor(0xFFFFFFFF);
        background.setChecked(Prefs.background(this));
        background.setPadding((int) (18 * dp), (int) (14 * dp), (int) (18 * dp), (int) (14 * dp));
        background.setBackground(pill());
        background.setOnCheckedChangeListener((v, on) -> Prefs.setBackground(this, on));
        col.addView(background, row(40));
        col.addView(label(getString(R.string.settings_background_hint), 15, 0x80FFFFFF, "sans-serif", 8));

        if (!Settings.canDrawOverlays(this)) {
            col.addView(label(getString(R.string.settings_overlay), 15, 0xFFFFB340, "sans-serif", 28));
        }
        setContentView(col);
        background.requestFocus();
    }

    @Override
    protected void onPause() {
        saveName();
        super.onPause();
    }

    private void saveName() {
        String before = Prefs.name(this);
        Prefs.setName(this, name.getText().toString());
        if (!Prefs.name(this).equals(before)) AirPlayService.restart(this);
    }

    private TextView label(String s, float sp, int color, String family, int topDp) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(family, Typeface.NORMAL));
        v.setPadding(0, (int) (topDp * dp), 0, 0);
        return v;
    }

    private TextView button(String s) {
        TextView v = label(s, 18, 0xFFFFFFFF, "sans-serif", 0);
        v.setFocusable(true);
        v.setClickable(true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
        v.setBackground(pill());
        return v;
    }

    /** Dim when idle, white outline when focused: the remote has to see where it is. */
    private StateListDrawable pill() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(0x33FFFFFF, 0xFFFFFFFF));
        s.addState(new int[]{}, shape(0x1AFFFFFF, 0x00FFFFFF));
        return s;
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(14 * dp);
        g.setStroke((int) (2 * dp), stroke);
        return g;
    }

    private LinearLayout.LayoutParams row(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = (int) (topDp * dp);
        return lp;
    }
}
