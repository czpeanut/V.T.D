package com.appgarage.dash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/**
 * Host for the driving screen.
 *
 * Repaint is driven by a fixed timer, not by sensor events. With 39 signals arriving at up to
 * 10 Hz each, invalidating per event would ask for several hundred full-screen software
 * redraws a second on a unit that can manage a dozen. Events only update the value array; the
 * timer decides when that becomes pixels.
 *
 * Touch: a long press opens settings. There is nothing else to reach -- the signal watcher
 * and the inventory dump were scaffolding for working out what this car publishes, and that
 * question is answered.
 */
public class DashActivity extends Activity implements SensorEventListener, Runnable {

    private static final int FRAME_MS = 70;          // ~14 fps, the target for this hardware
    private static final int SENSOR_US = 100000;     // ask for ~10 Hz

    private SensorManager sm;
    private DashView view;
    private final Handler handler = new Handler();
    private boolean hasBus;
    private long downAt;
    private float downX, downY;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new DashView(this);
        setContentView(view);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // re-read on every resume: this is how a change made in settings takes effect
        view.setCjk(SettingsActivity.LANG_ZH.equals(
                SettingsActivity.current(this, view.isCjk()
                        ? SettingsActivity.LANG_ZH : SettingsActivity.LANG_EN)));
        registerAll();
        handler.removeCallbacks(this);
        handler.postDelayed(this, FRAME_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(this);
        try { if (sm != null) sm.unregisterListener(this); } catch (Throwable ignored) {}
    }

    private void registerAll() {
        if (sm == null) return;
        hasBus = false;
        try {
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            if (all != null) for (int i = 0; i < all.size(); i++) {
                Sensor s = all.get(i);
                if (s.getType() == 13) hasBus = true;            // ENGINE_RPM => real car
                try { sm.registerListener(this, s, SENSOR_US); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        if (!hasBus) view.seedDemo();                            // emulator: show the layout
    }

    public void run() {
        view.invalidate();
        handler.postDelayed(this, FRAME_MS);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        try {
            float val = (e.values != null && e.values.length > 0) ? e.values[0] : 0f;
            view.setValue(e.sensor.getType(), val);              // no invalidate: see above
        } catch (Throwable ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN) {
            downAt = System.currentTimeMillis();
            downX = e.getX();
            downY = e.getY();
            return true;
        }
        if (a == MotionEvent.ACTION_UP) {
            long held = System.currentTimeMillis() - downAt;
            float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
            if (dx < 40f && dy < 40f && held > 700) {
                try { startActivity(new Intent(this, SettingsActivity.class)); }
                catch (Throwable ignored) {}
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    private float getWidth() { return view == null ? 1f : view.getWidth(); }
    private float getHeight() { return view == null ? 1f : view.getHeight(); }
}
