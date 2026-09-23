package com.appgarage.dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.io.InputStream;
import java.util.Random;

/**
 * The 800x480 driving screen.
 *
 * Built from the owner's layout, then adapted to what this car and this hardware can actually
 * do. Departures from the drawing, and why:
 *
 *   - GPS block and map thumbnail: dropped. All 39 signals are Ygomi CAN and none is a
 *     position; map data lives in the Linux navigation layer, out of Android's reach. That
 *     corner carries road speed, which the drawing was otherwise missing.
 *   - The decorative arrow above the car now shows which pedal is winning and how hard.
 *   - The empty band under the tyre callouts carries a friction circle, because types 20/21
 *     turned out to report g directly and that is the most interesting confirmed pair on the
 *     car.
 *   - An EV badge sits beside the gear. Nothing in the inventory reports the hybrid battery,
 *     so electric drive is inferred: zero rpm while the road speed is non-zero. It is the only
 *     hybrid state this unit can show, and the factory lower screen does not show it at all.
 *   - The lower-left callout in the drawing was labelled "front left" while pointing at the
 *     rear left wheel.
 *
 * Motion, all of it chosen to cost almost nothing:
 *
 *   - Every displayed value eases toward its target instead of snapping. At 14 fps this is
 *     the single largest difference between "a readout" and "an instrument".
 *   - The tachometer sweeps once to the redline and back at launch, the way a real cluster
 *     self-tests, then carries a peak-hold marker that rides the highest recent value and
 *     falls back slowly.
 *   - Warnings pulse rather than sit still, and a shift band across the top goes amber then
 *     flashes red as the redline approaches.
 *   - The friction circle drags a fading trail, so a corner leaves a visible shape.
 *
 * Drawing budget on a software-rendered API 10 unit with no GPU:
 *
 *   - Everything static is rendered ONCE into an off-screen bitmap that each frame blits
 *     before painting live parts: a few dozen shapes per frame instead of several hundred.
 *   - onDraw allocates nothing. Paints, Paths, RectFs, trail buffers and a char[] for numbers
 *     are all built up front. Dalvik's collector stops the world, so an allocation per frame
 *     is a visible stutter.
 *   - Glow is two strokes, wide and faint then narrow and bright. BlurMaskFilter is far too
 *     slow here.
 */
public class DashView extends View {

    // ---- signal types, all confirmed on this car ----
    private static final int RPM = 13, COOLANT = 14, SPEED = 17, GEAR = 22,
            ACCEL = 23, BRAKE = 24, STEER = 25, G_LAT = 20, G_LONG = 21,
            TP_FR = 36, TP_FL = 37, TP_RR = 38, TP_RL = 39;

    // ---- calibration ----
    // Type 12 EFFECTIVE_TORQUE is no longer on this screen. It was read as the electric
    // motor's signed torque, positive regenerating, and that reading never firmed up: gentle
    // acceleration showed LESS drive torque than the car holds at a standstill, and settling
    // it needed deliberate test drives. An uncalibrated number in an unknown unit does not
    // earn a full column on a screen that is read at a glance while driving. Every
    // measurement, and the two experiments that would settle it, are kept in
    // docs/sensors-vq35hr.md, so the finding outlives the gauge.
    //
    // The column it occupied now carries the two pedals, which are calibrated, continuous,
    // and describe something the driver can feel.
    private static final float COOLANT_MIN = 40f, COOLANT_MAX = 120f;
    private static final float COOLANT_COLD = 60f, COOLANT_WARN = 105f;
    private static final float TPMS_LOW = 30f, TPMS_HIGH = 44f;
    // A TPMS channel reads a flat zero until its wheel sensor has been heard from, which is
    // not the same thing as a flat tyre and must never be alarmed as one.
    private static final float TPMS_PRESENT = 1f;
    private static final float G_ALERT = 0.60f;
    private static final float STEER_FULL = 390f;   // measured full lock
    private static final float BRAKE_FULL = 90f;    // type 24 declares max 90
    // Type 23 declares a maximum of 1000 but peaked at 39.25 over a drive with ordinary
    // throttle, which reads as a percentage. On the declared scale the bar would barely move.
    private static final float ACCEL_FULL = 100f;
    private static final float PEDAL_DEAD = 1.5f;   // below this, call the pedal released
    private static final float G_FULL = 1.0f;       // friction circle outer ring

    // ---- palette ----
    private static final int BG_TOP = 0xFF060A12, BG_BOT = 0xFF0C1420;
    private static final int CYAN = 0xFF3FD2FF, CYAN_DIM = 0xFF1B4E66;
    private static final int WHITE = 0xFFEAF6FF, GREY = 0xFF55697C, DARK = 0xFF13212C;
    private static final int AMBER = 0xFFFFB020, RED = 0xFFFF4545, GREEN = 0xFF46E08A;
    private static final int PANEL = 0xCC0A1420;

    private static final int SWEEP_MS = 1700;       // launch self-test
    private static final int TRAIL = 22;            // friction-circle history

    // ---- live and displayed values ----
    private final float[] v = new float[64];
    private final boolean[] have = new boolean[64];
    private final float[] d = new float[64];        // eased, what actually gets drawn

    // ---- animation state ----
    private long t0, lastFrame;
    private float peakThr, peakBrk;       // trip peaks, one for each pedal
    private int lastGear = -999;
    private long gearFlash;
    private final float[] trailX = new float[TRAIL], trailY = new float[TRAIL];
    private int trailN;
    private float maxG;

    // ---- preallocated drawing state ----
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);   // labels, incl. CJK
    private final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);    // the packaged display face
    private float cellDigit = 0.6f, cellDot = 0.3f, cellMinus = 0.4f;
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final char[] buf = new char[20];
    private Bitmap staticLayer;
    private Bitmap carArt;
    private boolean carArtTried;
    private boolean cjk = true;

    // ---- geometry ----
    private float W, H;
    private float rpmX, rpmY0, rpmY1, barW, cooX;
    private float carCx, carCy, carW, carH;
    // Header cells, all one height and all framed like the gear, so the top row reads as
    // a single instrument rather than four differently-dressed readouts.
    private final RectF pedalBox = new RectF(), steerBox = new RectF(), coolBox = new RectF();
    private final RectF speedBox = new RectF(), statusBar = new RectF();
    private float gCx, gCy, gR;
    private final RectF[] tyreBox = new RectF[4];
    private final float[] tyreDotX = new float[4], tyreDotY = new float[4];
    private final int[] tyreType = { TP_FL, TP_FR, TP_RL, TP_RR };
    private final RectF gearBox = new RectF();

    public DashView(Context c) {
        super(c);
        for (int i = 0; i < 4; i++) tyreBox[i] = new RectF();
        // Labels keep a system typeface: Android falls back to DroidSansFallback for CJK
        // there, and a typeface loaded from assets does not fall back at all -- a Latin-only
        // display face would draw the Chinese labels as blanks.
        pText.setTypeface(Typeface.MONOSPACE);
        Typeface face = null;
        try { face = Typeface.createFromAsset(c.getAssets(), "dash.ttf"); }
        catch (Throwable t) { face = null; }        // absent or unreadable: fall back
        pNum.setTypeface(face != null ? face : Typeface.MONOSPACE);
        pNum.setTextAlign(Paint.Align.CENTER);      // drawNum positions each cell itself
        measureCells();
        // This Android layer is a guest system and its font set is not guaranteed. A
        // zero-width measurement means the glyph is missing: fall back rather than draw tofu.
        // The probe only picks the first-run default: if this Android layer has no CJK font
        // the glyph measures zero wide and English comes up. Settings overrides it after that.
        cjk = new Paint().measureText("轉") > 0.5f;
        t0 = lastFrame = System.currentTimeMillis();
    }

    public void setValue(int t, float val) { if (t >= 0 && t < 64) { v[t] = val; have[t] = true; } }
    public boolean isCjk() { return cjk; }
    public void setCjk(boolean on) {
        if (on == cjk) return;                  // no need to rebuild the backdrop for nothing
        cjk = on;
        staticLayer = null;
        invalidate();
    }

    private float g(int t) { return have[t] ? d[t] : 0f; }
    private boolean h(int t) { return have[t]; }

    /** representative values so the layout can be judged off-car */
    public void seedDemo() {
        setValue(RPM, 0f); setValue(COOLANT, 88f);
        setValue(SPEED, 64f); setValue(GEAR, 4f); setValue(ACCEL, 34f); setValue(BRAKE, 0f); setValue(STEER, 12f);
        setValue(G_LAT, 0.32f); setValue(G_LONG, -0.18f);
        setValue(TP_FL, 39.2f); setValue(TP_FR, 39.2f);
        setValue(TP_RL, 38.5f); setValue(TP_RR, 38.2f);
        for (int i = 0; i < 64; i++) d[i] = v[i];
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        super.onSizeChanged(w, hh, ow, oh);
        W = w; H = hh;
        staticLayer = null;

        barW = W * 0.0625f;
        rpmX = W * 0.0225f;
        cooX = W - rpmX - barW;
        rpmY0 = H * 0.225f;                      // headings own everything above this
        rpmY1 = H * 0.929f;

        // The EV badge is gone. It inferred electric drive from zero rpm at road speed, and
        // type 13 never once left zero across a whole trip, so there was nothing behind it.
        //
        // The header is now one row of identical cells: a small grey caption, a framed well,
        // a number centred in it. Before this the two columns wore their own larger left- and
        // right-aligned headings while the gear sat in a bracketed box, which read as three
        // designs sharing a screen. The outer two are held to a width that takes three digits
        // and no more, so neither language can push a caption into its neighbour.
        float cellT = H * 0.0583f, cellB = H * 0.1833f, cellW = W * 0.115f;
        pedalBox.set(rpmX, cellT, rpmX + cellW, cellB);
        gearBox.set(W * 0.185f, cellT, W * 0.285f, cellB);
        coolBox.set(cooX + barW - cellW, cellT, cooX + barW, cellB);

        // Steering takes the corner the status panel had. That leaves the centre clear for
        // the heading arrow to rise into, which is the whole reason the gap exists -- the
        // figure had been parked in it, in front of the very thing it describes.
        steerBox.set(W * 0.60f, cellT, W * 0.80f, cellB);

        // the supplied drawing is taller than it is wide, so height is what limits it
        carW = W * 0.255f;
        carH = H * 0.46f;
        carCx = W * 0.5f;
        carCy = H * 0.555f;

        float bw = W * 0.1875f, bh = H * 0.125f;
        float leftX = W * 0.11f, rightX = W * 0.7025f;
        float topY = H * 0.335f, botY = H * 0.585f;
        tyreBox[0].set(leftX,  topY, leftX + bw,  topY + bh);
        tyreBox[1].set(rightX, topY, rightX + bw, topY + bh);
        tyreBox[2].set(leftX,  botY, leftX + bw,  botY + bh);
        tyreBox[3].set(rightX, botY, rightX + bw, botY + bh);
        tyreDotX[0] = carCx - carW * 0.46f; tyreDotY[0] = carCy - carH * 0.22f;
        tyreDotX[1] = carCx + carW * 0.46f; tyreDotY[1] = carCy - carH * 0.22f;
        tyreDotX[2] = carCx - carW * 0.46f; tyreDotY[2] = carCy + carH * 0.28f;
        tyreDotX[3] = carCx + carW * 0.46f; tyreDotY[3] = carCy + carH * 0.28f;

        // circle plus its readouts must fit above the panel edge, so the labels sit beside
        // it rather than under it -- stacked underneath they landed at y=517 on a 480 screen
        gR = H * 0.080f;
        gCx = W * 0.15f;
        gCy = H * 0.855f;

        // What the car has to say now runs along the bottom, in the band the pedal bar used
        // to occupy. A wide shallow strip wants one line rather than two, so the detail
        // follows the condition instead of sitting under it. The left edge clears the peak-g
        // figure beside the friction circle.
        statusBar.set(W * 0.325f, H * 0.845f, W * 0.695f, H * 0.920f);

        // Road speed is framed like everything else, with its caption above the box and below
        // the rear-right tyre callout.
        speedBox.set(W * 0.705f, H * 0.800f, W * 0.895f, H * 0.960f);
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void onDraw(Canvas c) {
        if (W <= 0) return;
        long now = System.currentTimeMillis();
        float dt = (now - lastFrame) / 1000f;
        if (dt > 0.5f) dt = 0.5f;                  // after a pause, do not lurch
        lastFrame = now;
        step(now, dt);

        if (staticLayer == null) buildStatic();
        if (staticLayer != null) c.drawBitmap(staticLayer, 0, 0, null);
        else drawStatic(c);                        // out of memory: pay it every frame

        float pulse = 0.55f + 0.45f * (float) Math.sin(now * 0.009);
        drawPedalColumn(c);
        drawCoolant(c, pulse);
        drawGear(c, now);
        drawSteering(c);
        drawTyres(c, pulse);
        drawStatus(c, pulse);
        drawFriction(c);
        drawHeading(c);
        drawSpeed(c);
    }

    /**
     * Where the car is about to go: an arrow above the roof that bends with the steering angle
     * and lengthens with road speed. Straight and short at a standstill, leaning hard into the
     * turn under lock.
     *
     * The bend is deliberately not one-to-one with the wheel. Full lock is 390 degrees and an
     * arrow rotated that far would be pointing backwards; it maps to a readable lean instead,
     * so the shape tracks the wheel without becoming nonsense.
     */
    private void drawHeading(Canvas c) {
        if (!h(STEER) && !h(SPEED)) return;
        float t = h(STEER) ? d[STEER] / STEER_FULL : 0f;
        if (t < -1f) t = -1f; else if (t > 1f) t = 1f;
        float spd = h(SPEED) ? d[SPEED] / 120f : 0f;
        if (spd < 0f) spd = 0f; else if (spd > 1f) spd = 1f;

        float baseX = carCx, baseY = carCy - carH * 0.52f;   // low enough that the
                                                             // head clears the status panel
        float len = carH * (0.17f + 0.14f * spd);      // short at a standstill, long at speed
        float tipX = baseX + t * carW * 0.44f;      // stays clear of the status panel at lock
        float tipY = baseY - len;
        float ctrlX = baseX + t * carW * 0.16f;
        float ctrlY = baseY - len * 0.55f;

        int alpha = (int) (0x66 + 0x99 * spd);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        path.rewind();
        path.moveTo(baseX, baseY);
        path.quadTo(ctrlX, ctrlY, tipX, tipY);
        p.setColor((CYAN & 0x00FFFFFF) | ((alpha / 3) << 24));   // glow pass
        p.setStrokeWidth(carW * 0.100f);
        c.drawPath(path, p);
        p.setColor((CYAN & 0x00FFFFFF) | (alpha << 24));
        p.setStrokeWidth(carW * 0.038f);
        c.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.BUTT);

        // head, squared to the curve's end tangent so it never looks bolted on
        float dx = tipX - ctrlX, dy = tipY - ctrlY;
        float m = (float) Math.sqrt(dx * dx + dy * dy);
        if (m < 0.001f) return;
        dx /= m; dy /= m;
        float px = -dy, py = dx;
        float hl = carH * 0.090f, hw = carW * 0.100f;
        path.rewind();
        path.moveTo(tipX + dx * hl, tipY + dy * hl);
        path.lineTo(tipX + px * hw, tipY + py * hw);
        path.lineTo(tipX - px * hw, tipY - py * hw);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor((CYAN & 0x00FFFFFF) | ((alpha / 3) << 24));
        c.drawPath(path, p);
        p.setColor((CYAN & 0x00FFFFFF) | (alpha << 24));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        c.drawPath(path, p);
    }

    /** ease every displayed value toward its target and advance the animation state */
    private void step(long now, float dt) {
        ease(RPM, 0.30f, dt);
        ease(COOLANT, 0.06f, dt); ease(SPEED, 0.22f, dt);
        ease(STEER, 0.40f, dt); ease(ACCEL, 0.35f, dt); ease(BRAKE, 0.35f, dt);
        ease(G_LAT, 0.30f, dt); ease(G_LONG, 0.30f, dt);
        for (int i = 0; i < 4; i++) d[tyreType[i]] = v[tyreType[i]];   // pressures never jump
        d[GEAR] = v[GEAR];

        // trip peaks, one per pedal: the hardest it has been accelerated and braked
        float thr = pedalFrac(ACCEL, ACCEL_FULL), brk = pedalFrac(BRAKE, BRAKE_FULL);
        if (thr > peakThr) peakThr = thr;
        if (brk > peakBrk) peakBrk = brk;

        int gear = h(GEAR) ? (int) (v[GEAR] + 0.5f) : -999;
        if (gear != lastGear) { lastGear = gear; gearFlash = now + 320; }

        // friction-circle trail
        if (h(G_LAT) || h(G_LONG)) {
            for (int i = TRAIL - 1; i > 0; i--) { trailX[i] = trailX[i - 1]; trailY[i] = trailY[i - 1]; }
            trailX[0] = g(G_LAT) / G_FULL;
            trailY[0] = g(G_LONG) / G_FULL;
            if (trailN < TRAIL) trailN++;
            float mag = (float) Math.sqrt(trailX[0] * trailX[0] + trailY[0] * trailY[0]) * G_FULL;
            if (mag > maxG) maxG = mag;
        }
    }

    private void ease(int t, float perFrame, float dt) {
        if (!have[t]) return;
        float a = perFrame * dt * 14f;              // perFrame is the step at ~14 fps
        if (a > 1f) a = 1f;
        d[t] += (v[t] - d[t]) * a;
    }

    /** 0..1 of a pedal's travel, clamped. */
    private float pedalFrac(int t, float full) {
        float f = h(t) ? d[t] / full : 0f;
        return f < 0f ? 0f : f > 1f ? 1f : f;
    }

    // ------------------------------------------------------------------ live parts

    /**
     * The two pedals, as one bipolar column with rest in the middle: throttle climbs in cyan,
     * brake descends in red. This is the horizontal bar that used to sit under the car, stood
     * on end -- the same reading, in the space the torque column gave up, with twelve segments
     * a side instead of a strip shared between both feet.
     *
     * One column rather than two parallel ones, because the two are almost never pressed at
     * once and because two bars inside a fifty-pixel well would each be too thin to read at a
     * glance, which is the only way this screen is ever read.
     */
    private void drawPedalColumn(Canvas c) {
        final int HALF = 12;                        // segments each side of rest
        float inner = barW * 0.18f;
        float x0 = rpmX + inner, x1 = rpmX + barW - inner;
        float span = (rpmY1 - rpmY0) - inner * 2f;
        float mid = rpmY0 + inner + span * 0.5f;
        float seg = span * 0.5f / HALF;

        float thr = pedalFrac(ACCEL, ACCEL_FULL);
        float brk = pedalFrac(BRAKE, BRAKE_FULL);
        long age = System.currentTimeMillis() - t0;
        if (age < SWEEP_MS) {
            // one full cycle either side of rest, which shows the column is bipolar before a
            // foot has touched anything
            float sweep = (float) Math.sin(age / (double) SWEEP_MS * Math.PI * 2.0);
            if (sweep > thr) thr = sweep;
            if (-sweep > brk) brk = -sweep;
        }
        int onUp = (int) (thr * HALF + 0.5f);
        int onDn = (int) (brk * HALF + 0.5f);

        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < HALF; i++) {
            float pad = seg * 0.14f;
            segment(c, x0, mid - (i + 1) * seg + pad, x1, mid - i * seg - pad, i < onUp, CYAN);
            segment(c, x0, mid + i * seg + pad, x1, mid + (i + 1) * seg - pad, i < onDn, RED);
        }

        // rest line, so the middle is unmistakable at a glance
        p.setColor(0xCCEAF6FF);
        c.drawRect(rpmX - 1f, mid - 1.2f, rpmX + barW + 1f, mid + 1.2f, p);

        if (peakThr > 0.03f) peakMark(c, mid - peakThr * span * 0.5f, CYAN);
        if (peakBrk > 0.03f) peakMark(c, mid + peakBrk * span * 0.5f, RED);

        // The number follows whichever foot is down, brake first: that is the reading you
        // want without having to work out which of two figures you are looking at.
        boolean braking = h(BRAKE) && d[BRAKE] > PEDAL_DEAD;
        boolean onGas = !braking && h(ACCEL) && d[ACCEL] > PEDAL_DEAD;
        int live = braking ? BRAKE : ACCEL;
        int n = h(live) ? fmt(d[live], 0) : dashes();
        drawNum(c, n, pedalBox.centerX(), H * 0.1583f, 1, H * 0.090f,
                !h(live) ? GREY : braking ? RED : onGas ? CYAN : WHITE);

        if (braking || onGas) {
            pText.setColor(braking ? RED : CYAN);
            String dir = braking ? (cjk ? "刹車" : "BRAKE") : (cjk ? "加速" : "THROTTLE");
            pText.setTextAlign(Paint.Align.CENTER);
            pText.setTextSize(fitSize(dir, pedalBox.width(), H * 0.036f));
            c.drawText(dir, pedalBox.centerX(), H * 0.201f, pText);
        }
    }

    private void segment(Canvas c, float l, float t, float rr, float b, boolean lit, int col) {
        if (lit) {
            p.setColor((col & 0x00FFFFFF) | 0x55000000);        // bloom
            c.drawRect(l - 2.5f, t - 2.5f, rr + 2.5f, b + 2.5f, p);
            p.setColor(col);
        } else {
            p.setColor((col & 0x00FFFFFF) | 0x1E000000);
        }
        c.drawRect(l, t, rr, b, p);
    }

    private void peakMark(Canvas c, float y, int col) {
        p.setColor(col);
        c.drawRect(rpmX - 2f, y - 1.5f, rpmX + barW + 2f, y + 1.5f, p);
    }

    private void drawCoolant(Canvas c, float pulse) {
        float inner = barW * 0.18f;
        float x0 = cooX + inner, x1 = cooX + barW - inner;
        float y0 = rpmY0 + inner, y1 = rpmY1 - inner;
        float frac = h(COOLANT) ? (d[COOLANT] - COOLANT_MIN) / (COOLANT_MAX - COOLANT_MIN) : 0f;
        if (frac < 0) frac = 0; else if (frac > 1) frac = 1;
        float top = y1 - (y1 - y0) * frac;
        boolean hot = h(COOLANT) && d[COOLANT] >= COOLANT_WARN;
        boolean cold = h(COOLANT) && d[COOLANT] < COOLANT_COLD;
        int col = hot ? RED : cold ? AMBER : CYAN;

        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF10202B);
        c.drawRect(x0, y0, x1, y1, p);
        if (h(COOLANT) && frac > 0.01f) {
            int a = hot ? (int) (0x50 + 0x50 * pulse) : 0x66;
            p.setColor((col & 0x00FFFFFF) | (a << 24));
            c.drawRect(x0, top, x1, y1, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.4f);
            p.setColor(col);
            path.rewind();
            path.moveTo(x0, top);
            path.quadTo((x0 + x1) * 0.5f, top - 4f, x1, top);
            c.drawPath(path, p);
        }
        int n = h(COOLANT) ? fmt(d[COOLANT], 0) : dashes();
        drawNum(c, n, coolBox.centerX(), H * 0.1583f, 1, H * 0.090f,
                h(COOLANT) ? (hot ? RED : cold ? AMBER : WHITE) : GREY);

        // VQ35 does not want revs until it is warm, so say so plainly
        if (cold) {
            pText.setColor((AMBER & 0x00FFFFFF) | ((int) (0x80 + 0x7F * pulse) << 24));
            String warm = cjk ? "暖車中" : "WARMING";
            pText.setTextAlign(Paint.Align.CENTER);
            pText.setTextSize(fitSize(warm, coolBox.width(), H * 0.036f));
            c.drawText(warm, coolBox.centerX(), H * 0.201f, pText);
        }
    }

    private void drawGear(Canvas c, long now) {
        boolean flash = now < gearFlash;
        if (flash) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x333FD2FF);
            c.drawRect(gearBox, p);
        }
        pNum.setColor(h(GEAR) ? (flash ? WHITE : CYAN) : GREY);
        pNum.setTextSize(H * 0.105f);
        c.drawText(gearText(), gearBox.centerX(), H * 0.1583f, pNum);
    }

    /** P=1 R=2 N=3 D=4, M1..M7 = 16..22, confirmed on-car */
    private String gearText() {
        if (!h(GEAR)) return "--";
        int x = (int) (d[GEAR] + 0.5f);
        switch (x) {
            case 1: return "P"; case 2: return "R"; case 3: return "N"; case 4: return "D";
            case 16: return "M1"; case 17: return "M2"; case 18: return "M3"; case 19: return "M4";
            case 20: return "M5"; case 21: return "M6"; case 22: return "M7";
            default: return "--";
        }
    }

    private void drawSteering(Canvas c) {
        float deg = g(STEER);                       // degrees directly, right positive
        int n = h(STEER) ? fmt(deg, 0) : dashes();
        // sized for the widest it ever gets, "-390": a minus and three digits in the cell
        drawNum(c, n, steerBox.centerX(), H * 0.1583f, 1, H * 0.095f, h(STEER) ? WHITE : GREY);
    }

    /**
     * What the car wants to say, along the bottom where the pedal bar used to run. The strip
     * is wide and shallow, so the condition and its detail share one line rather than stacking:
     * the condition in its colour, the figure behind it in grey. Worst first, so a glance is
     * enough.
     *
     * A tyre still acquiring its sensor is reported as such and never as a pressure fault:
     * those two states look identical in the raw value and mean opposite things.
     */
    private void drawStatus(Canvas c, float pulse) {
        String head, detail;
        int col;
        int worst = -1;
        boolean anyAcquiring = false;

        if (h(COOLANT) && d[COOLANT] >= COOLANT_WARN) {
            head = cjk ? "水溫過高" : "COOLANT HOT";
            detail = fmtToStr(d[COOLANT], 0) + " C";
            col = RED;
        } else {
            for (int i = 0; i < 4; i++) {
                int t = tyreType[i];
                if (!h(t)) continue;
                if (d[t] < TPMS_PRESENT) { anyAcquiring = true; continue; }
                if (d[t] < TPMS_LOW || d[t] > TPMS_HIGH) { worst = i; break; }
            }
            float gm = gMagnitude();
            if (worst >= 0) {
                boolean low = d[tyreType[worst]] < TPMS_LOW;
                head = cjk ? (low ? "胎壓過低" : "胎壓過高") : (low ? "TYRE LOW" : "TYRE HIGH");
                detail = wheelShort(worst) + " " + fmtToStr(d[tyreType[worst]], 1);
                col = AMBER;
            } else if (gm >= G_ALERT) {
                head = cjk ? "G 值偏高" : "HIGH G";
                detail = fmtToStr(gm, 2) + " g";
                col = AMBER;
            } else if (h(COOLANT) && d[COOLANT] < COOLANT_COLD) {
                head = cjk ? "暖車中" : "WARMING";
                detail = fmtToStr(d[COOLANT], 0) + " C";
                col = AMBER;
            } else if (anyAcquiring) {
                head = cjk ? "胎壓偵測中" : "TPMS WAIT";
                detail = cjk ? "等待感測器" : "no sensor yet";
                col = GREY;
            } else {
                head = cjk ? "狀態正常" : "ALL OK";
                detail = "";
                col = GREEN;
            }
        }

        if (col == RED || col == AMBER) {
            p.setStyle(Paint.Style.FILL);
            p.setColor((col & 0x00FFFFFF) | ((int) (0x16 + 0x22 * pulse) << 24));
            c.drawRect(statusBar, p);
        }

        // Messages vary in length and several are far longer in English than in Chinese, so
        // both pieces are fitted rather than assumed to fit, and the pair is centred together.
        float room = statusBar.width() - W * 0.020f;
        boolean hasDetail = detail.length() > 0;
        float gap = hasDetail ? W * 0.018f : 0f;

        float hs = fitSize(head, hasDetail ? room * 0.64f : room, H * 0.055f);
        pText.setTextSize(hs);
        float hw = pText.measureText(head);

        float ds = 0f, dw = 0f;
        if (hasDetail) {
            ds = fitSize(detail, room - hw - gap, H * 0.038f);
            pText.setTextSize(ds);
            dw = pText.measureText(detail);
        }

        float sx = statusBar.centerX() - (hw + gap + dw) * 0.5f;
        pText.setTextAlign(Paint.Align.LEFT);
        pText.setColor(col);
        pText.setTextSize(hs);
        c.drawText(head, sx, H * 0.898f, pText);
        if (hasDetail) {
            pText.setColor(GREY);
            pText.setTextSize(ds);
            c.drawText(detail, sx + hw + gap, H * 0.898f, pText);
        }
    }

    /** largest size at or below want that keeps the string inside room */
    private float fitSize(String t, float room, float want) {
        pText.setTextSize(want);
        float w = pText.measureText(t);
        if (w <= room || w <= 0f) return want;
        float scaled = want * room / w;
        return scaled < want * 0.55f ? want * 0.55f : scaled;
    }

    private float gMagnitude() {
        if (!h(G_LAT) && !h(G_LONG)) return 0f;
        float a = d[G_LAT], b = d[G_LONG];
        return (float) Math.sqrt(a * a + b * b);
    }

    private String wheelShort(int i) {
        if (cjk) {
            switch (i) { case 0: return "左前"; case 1: return "右前";
                         case 2: return "左後"; default: return "右後"; }
        }
        switch (i) { case 0: return "FL"; case 1: return "FR"; case 2: return "RL"; default: return "RR"; }
    }

    /** the status panel needs real Strings, and it changes rarely enough for that to be fine */
    private String fmtToStr(float v, int dec) {
        int n = fmt(v, dec);
        return new String(buf, 0, n);
    }

    private void drawTyres(Canvas c, float pulse) {
        for (int i = 0; i < 4; i++) {
            RectF b = tyreBox[i];
            int t = tyreType[i];
            boolean ok = h(t);
            float psi = d[t];
            boolean acquiring = ok && psi < TPMS_PRESENT;     // sensor not heard from yet
            boolean bad = ok && !acquiring && (psi < TPMS_LOW || psi > TPMS_HIGH);
            int col = (!ok || acquiring) ? GREY : bad ? AMBER : WHITE;

            if (bad) {                              // a soft tyre must not sit quietly
                p.setStyle(Paint.Style.FILL);
                p.setColor((AMBER & 0x00FFFFFF) | ((int) (0x14 + 0x1C * pulse) << 24));
                c.drawRect(b, p);
            }
            float nx = b.left + W * 0.012f, ny = b.bottom - H * 0.018f;
            if (acquiring) {
                String wait = cjk ? "偵測中" : "ACQUIRING";
                pText.setTextAlign(Paint.Align.LEFT);
                pText.setColor(GREY);
                pText.setTextSize(fitSize(wait, b.width() - W * 0.030f, H * 0.050f));
                c.drawText(wait, nx, ny, pText);
            } else {
                int n = ok ? fmt(psi, 1) : dashes();
                float nw = numWidth(n, H * 0.070f);
                drawNum(c, n, nx, ny, 0, H * 0.070f, col);
                pText.setTextSize(H * 0.040f);
                pText.setTextAlign(Paint.Align.LEFT);
                pText.setColor(GREY);
                c.drawText("PSI", nx + nw + W * 0.012f, ny, pText);
            }

            p.setStyle(Paint.Style.FILL);
            p.setColor((!ok || acquiring) ? GREY : bad ? AMBER : GREEN);
            c.drawCircle(b.right - W * 0.018f, b.top + H * 0.030f, H * 0.013f, p);
        }
    }

    /**
     * Friction circle. Types 20 and 21 report g directly, so this needs no scaling beyond the
     * ring radius. The trail is what makes it readable: a corner leaves a shape rather than a
     * dot that has already moved on.
     */
    private void drawFriction(Canvas c) {
        boolean ok = h(G_LAT) || h(G_LONG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f);
        p.setColor(CYAN_DIM);
        c.drawCircle(gCx, gCy, gR, p);
        c.drawCircle(gCx, gCy, gR * 0.5f, p);
        c.drawLine(gCx - gR, gCy, gCx + gR, gCy, p);
        c.drawLine(gCx, gCy - gR, gCx, gCy + gR, p);

        if (ok) {
            p.setStyle(Paint.Style.FILL);
            for (int i = trailN - 1; i > 0; i--) {
                float a = 1f - (float) i / TRAIL;
                p.setColor((CYAN & 0x00FFFFFF) | ((int) (0x10 + 0x55 * a * a) << 24));
                c.drawCircle(gCx + clamp1(trailX[i]) * gR, gCy + clamp1(trailY[i]) * gR,
                        1.5f + 2.2f * a, p);
            }
            p.setColor(WHITE);
            c.drawCircle(gCx + clamp1(trailX[0]) * gR, gCy + clamp1(trailY[0]) * gR, 4.2f, p);
        }

        float lx = gCx + gR + W * 0.018f;
        pText.setTextAlign(Paint.Align.LEFT);
        pText.setTextSize(H * 0.036f);
        pText.setColor(GREY);
        c.drawText(cjk ? "G 最大" : "PEAK G", lx, gCy - H * 0.010f, pText);
        int n = ok ? fmt(maxG, 2) : dashes();
        drawNum(c, n, lx, gCy + H * 0.052f, 0, H * 0.052f, ok ? CYAN : GREY);
    }

    private static float clamp1(float x) { return x < -1f ? -1f : x > 1f ? 1f : x; }

    private void drawSpeed(Canvas c) {
        int n = h(SPEED) ? fmt(d[SPEED], 0) : dashes();
        drawNum(c, n, speedBox.centerX(), H * 0.930f, 1, H * 0.130f, h(SPEED) ? WHITE : GREY);
    }

    // ------------------------------------------------------------------ static layer

    private void buildStatic() {
        try {
            // 8888 first: the backdrop is a long, dark gradient and 565 bands visibly across
            // it. Twice the memory, but it is one bitmap and it buys the whole look.
            Bitmap bm;
            try {
                bm = Bitmap.createBitmap((int) W, (int) H, Bitmap.Config.ARGB_8888);
            } catch (Throwable oom) {
                bm = Bitmap.createBitmap((int) W, (int) H, Bitmap.Config.RGB_565);
            }
            drawStatic(new Canvas(bm));
            staticLayer = bm;
            // the artwork now lives inside the static layer; holding the source as well just
            // occupies heap that Dalvik would rather have back
            if (carArt != null) { carArt.recycle(); carArt = null; carArtTried = false; }
        } catch (Throwable t) {
            staticLayer = null;                     // no memory: fall back to live drawing
        }
    }

    /**
     * The whole backdrop, composited once. Everything here is free per frame, so it is worth
     * spending on: a three-stop wash, a vignette, arcs and a pool of light behind the car,
     * a starfield with a few glints, tick scales beside both columns, and corner brackets
     * instead of plain rectangles. None of it costs anything once the bitmap exists.
     */
    private void drawStatic(Canvas c) {
        backdrop(c);
        arcs(c);
        pool(c);
        drawCar(c);

        // both scales face inward: against the screen edge they were half off the panel
        columnScale(c, rpmX + barW, false, 6);      // bipolar: six a side of rest
        columnScale(c, cooX, true, 4);              // 40..120 C, a tick per 20
        panel(c, rpmX, rpmY0, rpmX + barW, rpmY1);
        panel(c, cooX, rpmY0, cooX + barW, rpmY1);

        // one header row, four identical cells, every number framed the way the gear is
        headerCell(c, pedalBox, cjk ? "踏板" : "PEDALS");
        headerCell(c, gearBox,  cjk ? "檔位" : "GEAR");
        headerCell(c, steerBox, cjk ? "轉向角" : "STEERING");
        headerCell(c, coolBox,  cjk ? "水溫" : "COOLANT");

        for (int i = 0; i < 4; i++) {
            RectF b = tyreBox[i];
            boolean left = i == 0 || i == 2;
            float ax = left ? b.right : b.left, ay = b.centerY();
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0x333FD2FF);
            p.setStrokeWidth(3.5f);
            c.drawLine(ax, ay, tyreDotX[i], tyreDotY[i], p);        // glow under the leader
            p.setColor(CYAN_DIM);
            p.setStrokeWidth(1.3f);
            c.drawLine(ax, ay, tyreDotX[i], tyreDotY[i], p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x443FD2FF);                                  // node at the halfway point
            float mx = (ax + tyreDotX[i]) * 0.5f, my = (ay + tyreDotY[i]) * 0.5f;
            c.drawCircle(mx, my, 2.6f, p);
            p.setColor(0x553FD2FF);
            c.drawCircle(tyreDotX[i], tyreDotY[i], 6.5f, p);         // halo on the anchor
            p.setColor(CYAN);
            c.drawCircle(tyreDotX[i], tyreDotY[i], 3.0f, p);
            panel(c, b.left, b.top, b.right, b.bottom);
            pText.setColor(GREY);
            pText.setTextAlign(Paint.Align.LEFT);
            pText.setTextSize(fitSize(tyreLabel(i), b.width() - W * 0.050f, H * 0.038f));
            c.drawText(tyreLabel(i), b.left + W * 0.012f, b.top + H * 0.038f, pText);
        }

        // The status strip carries no caption. It labels itself -- a word reading STATUS
        // above a line reading ALL OK is one word too many.
        panel(c, statusBar.left, statusBar.top, statusBar.right, statusBar.bottom);

        panel(c, speedBox.left, speedBox.top, speedBox.right, speedBox.bottom);
        label(c, cjk ? "車速 km/h" : "SPEED km/h", speedBox.centerX(), speedBox.width(),
                speedBox.top - H * 0.018f);
    }

    /**
     * The car in the middle of the screen. If assets/car.png exists it is used; the vector
     * outline below is only the fallback.
     *
     * Using a bitmap here is cheaper than the vector version, not more expensive: the car is
     * part of the static layer and is therefore drawn exactly once, so this is a single scaled
     * blit against a Path that would otherwise be stroked twice for its glow. The source is
     * decoded once, downsampled if it is larger than it needs to be, and released as soon as
     * it has been composited.
     */
    private void drawCar(Canvas c) {
        Bitmap art = carArt();
        if (art != null) {
            float sw = art.getWidth(), sh = art.getHeight();
            float scale = Math.min(carW / sw, carH / sh);       // fit, preserving aspect
            float w = sw * scale, hgt = sh * scale;
            r.set(carCx - w * 0.5f, carCy - hgt * 0.5f, carCx + w * 0.5f, carCy + hgt * 0.5f);
            // Opaque white, explicitly. drawBitmap multiplies by the paint's alpha, and the
            // paint arrives here still carrying the last arc's 0x0A -- which drew the car at
            // four percent, i.e. invisibly. The desktop preview could not catch this: Java2D's
            // drawImage takes no paint, so there it always rendered at full strength.
            p.setColor(0xFFFFFFFF);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setFilterBitmap(true);
            c.drawBitmap(art, null, r, p);
            p.setFilterBitmap(false);
            return;
        }
        drawCarFallback(c);
    }

    /** decode assets/car.png once, downsampled to roughly the size it will be drawn at */
    private Bitmap carArt() {
        if (carArtTried) return carArt;
        carArtTried = true;
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContext().getAssets().open("car.png");
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            in = null;

            int sample = 1;
            int want = (int) Math.max(carW, carH) * 2;           // headroom for the scale-down
            if (want > 0) while (bounds.outWidth / (sample * 2) >= want) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;       // the drawing needs its alpha
            in = getContext().getAssets().open("car.png");
            carArt = BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable t) {
            carArt = null;                                       // absent or unreadable: fall back
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
        return carArt;
    }

    /** placeholder used until assets/car.png is supplied */
    private void drawCarFallback(Canvas c) {
        float hw = carW * 0.5f, hh = carH * 0.5f, x = carCx, y = carCy;
        path.rewind();
        path.moveTo(x - hw, y + hh * 0.50f);
        path.lineTo(x - hw, y - hh * 0.16f);
        path.quadTo(x - hw * 0.95f, y - hh * 0.50f, x - hw * 0.60f, y - hh * 0.60f);
        path.lineTo(x - hw * 0.42f, y - hh * 0.93f);
        path.quadTo(x, y - hh * 1.04f, x + hw * 0.42f, y - hh * 0.93f);
        path.lineTo(x + hw * 0.60f, y - hh * 0.60f);
        path.quadTo(x + hw * 0.95f, y - hh * 0.50f, x + hw, y - hh * 0.16f);
        path.lineTo(x + hw, y + hh * 0.50f);
        path.quadTo(x + hw * 0.88f, y + hh * 0.74f, x + hw * 0.52f, y + hh * 0.76f);
        path.lineTo(x - hw * 0.52f, y + hh * 0.76f);
        path.quadTo(x - hw * 0.88f, y + hh * 0.74f, x - hw, y + hh * 0.50f);
        path.close();
        path.moveTo(x - hw * 0.50f, y - hh * 0.58f);
        path.quadTo(x, y - hh * 0.70f, x + hw * 0.50f, y - hh * 0.58f);
        path.lineTo(x + hw * 0.60f, y - hh * 0.20f);
        path.lineTo(x - hw * 0.60f, y - hh * 0.20f);
        path.close();
        path.addRect(x - hw * 0.92f, y + hh * 0.00f, x - hw * 0.44f, y + hh * 0.20f,
                Path.Direction.CW);
        path.addRect(x + hw * 0.44f, y + hh * 0.00f, x + hw * 0.92f, y + hh * 0.20f,
                Path.Direction.CW);
        path.addRect(x - hw * 0.28f, y + hh * 0.50f, x + hw * 0.28f, y + hh * 0.64f,
                Path.Direction.CW);
        glowPath(c, path, CYAN, carW * 0.030f);
    }

    // ------------------------------------------------------------------ helpers

    private String tyreLabel(int i) {
        if (cjk) {
            switch (i) {
                case 0: return "左前胎壓";
                case 1: return "右前胎壓";
                case 2: return "左後胎壓";   // the drawing said "front left"
                default: return "右後胎壓";
            }
        }
        switch (i) {
            case 0: return "FRONT LEFT"; case 1: return "FRONT RIGHT";
            case 2: return "REAR LEFT"; default: return "REAR RIGHT";
        }
    }

    /** three-stop wash plus a vignette, so the panel has a centre and edges rather than a slab */
    private void backdrop(Canvas c) {
        p.setDither(true);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, 0, 0, H,
                new int[] { 0xFF05080F, BG_TOP, 0xFF0E1826, BG_BOT },
                new float[] { 0f, 0.28f, 0.72f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(W * 0.5f, H * 0.46f, W * 0.72f,
                new int[] { 0x00000000, 0x00000000, 0x5C000000 },
                new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        p.setDither(false);
    }

    /** faint concentric arcs centred on the car, so the middle of the screen has depth */
    private void arcs(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 6; i++) {
            float rad = carW * (0.95f + i * 0.30f);
            p.setColor(0x0E3FD2FF - (i * 0x01000000 > 0 ? 0 : 0));
            p.setColor((CYAN & 0x00FFFFFF) | ((0x14 - i * 2) << 24));
            p.setStrokeWidth(i == 2 ? 1.6f : 1.0f);
            r.set(carCx - rad, carCy - rad, carCx + rad, carCy + rad);
            c.drawArc(r, 200f, 140f, false, p);
            c.drawArc(r, 20f, 140f, false, p);
        }
    }

    /** a pool of light so the car sits in something instead of floating on black */
    private void pool(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(carCx, carCy, carW * 1.25f,
                new int[] { 0x2A3FD2FF, 0x0E3FD2FF, 0x00000000 },
                new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(carCx - carW * 1.3f, carCy - carW * 1.3f,
                   carCx + carW * 1.3f, carCy + carW * 1.3f, p);
        p.setShader(null);
    }

    /** tick scale down the outer edge of a column: longer marks at the round numbers */
    private void columnScale(Canvas c, float edgeX, boolean leftOfColumn, int divisions) {
        float inner = barW * 0.18f;
        float y0 = rpmY0 + inner, y1 = rpmY1 - inner;
        p.setStyle(Paint.Style.STROKE);
        for (int i = 0; i <= divisions * 2; i++) {
            boolean major = (i % 2) == 0;
            float y = y1 - (y1 - y0) * i / (divisions * 2f);
            float len = major ? 7f : 3.5f;
            p.setStrokeWidth(major ? 1.6f : 1.0f);
            p.setColor(major ? 0x993FD2FF : 0x443FD2FF);
            if (leftOfColumn) c.drawLine(edgeX - 3f - len, y, edgeX - 3f, y, p);
            else c.drawLine(edgeX + 3f, y, edgeX + 3f + len, y, p);
        }
    }

    /**
     * Panel chrome: a translucent well, a hairline, and bright corner brackets. The brackets
     * are what stop these reading as plain rectangles, and they cost nothing here.
     */
    private void panel(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, t, 0, b, 0xD8101E2C, PANEL, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.1f);
        p.setColor(0x33FFFFFF & 0x334A7A94);
        p.setColor(0x4A2F5E74);
        c.drawRect(r, p);

        float len = Math.min(rr - l, b - t) * 0.26f;
        if (len > 16f) len = 16f;
        p.setStrokeWidth(2.4f);
        p.setColor(0x553FD2FF);
        corners(c, l, t, rr, b, len);
        p.setStrokeWidth(1.2f);
        p.setColor(CYAN);
        corners(c, l, t, rr, b, len);
    }

    private void corners(Canvas c, float l, float t, float rr, float b, float len) {
        c.drawLine(l, t + len, l, t, p); c.drawLine(l, t, l + len, t, p);
        c.drawLine(rr - len, t, rr, t, p); c.drawLine(rr, t, rr, t + len, p);
        c.drawLine(l, b - len, l, b, p); c.drawLine(l, b, l + len, b, p);
        c.drawLine(rr - len, b, rr, b, p); c.drawLine(rr, b, rr, b - len, p);
    }

    /** a framed well with its small grey caption above it: the gear's dress, worn by all */
    private void headerCell(Canvas c, RectF b, String caption) {
        panel(c, b.left, b.top, b.right, b.bottom);
        label(c, caption, b.centerX(), b.width(), b.top - H * 0.0125f);
    }

    private void label(Canvas c, String s, float cx, float budget, float baseline) {
        pText.setColor(GREY);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setTextSize(fitSize(s, budget - W * 0.010f, H * 0.042f));
        c.drawText(s, cx, baseline, pText);
    }

    /** two-pass glow: wide and faint, then narrow and bright */
    /**
     * Stacked strokes standing in for a blur. Four passes rather than two: this runs once, in
     * the static layer, so the extra passes are free and the falloff is much smoother.
     */
    private void glowPath(Canvas c, Path pathIn, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        int[] alpha = { 0x14, 0x24, 0x48, 0xFF };
        float[] mul = { 1.9f, 1.15f, 0.62f, 0.26f };
        for (int i = 0; i < 4; i++) {
            p.setColor((color & 0x00FFFFFF) | (alpha[i] << 24));
            p.setStrokeWidth(width * mul[i]);
            c.drawPath(pathIn, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    /**
     * Measure the widest digit once, as a fraction of the text size, so numbers can be drawn
     * on a fixed cell.
     *
     * Only one of the display faces worth using has tabular figures; in the rest a 1 is
     * narrower than a 0, so a value counting up would shuffle every digit sideways. Rather
     * than let that dictate the typeface, each character is drawn centred in a cell of its own
     * and the font choice stays free.
     */
    private void measureCells() {
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setTypeface(pNum.getTypeface());
        m.setTextSize(100f);
        char[] one = new char[1];
        float mx = 0f;
        for (char ch = '0'; ch <= '9'; ch++) {
            one[0] = ch;
            float w = m.measureText(one, 0, 1);
            if (w > mx) mx = w;
        }
        if (mx <= 0f) return;                       // measurement failed: keep the defaults
        cellDigit = mx / 100f;
        one[0] = '.'; cellDot = m.measureText(one, 0, 1) / 100f;
        one[0] = '-'; cellMinus = m.measureText(one, 0, 1) / 100f;
    }

    private float cellFor(char ch) {
        if (ch == '.') return cellDot;
        if (ch == '-') return cellMinus;
        return cellDigit;
    }

    private float numWidth(int n, float size) {
        float w = 0f;
        for (int i = 0; i < n; i++) w += cellFor(buf[i]) * size;
        return w;
    }

    /** align: 0 left, 1 centre, 2 right. Allocates nothing. */
    private void drawNum(Canvas c, int n, float x, float y, int align, float size, int color) {
        pNum.setTextSize(size);
        pNum.setColor(color);
        float total = numWidth(n, size);
        float sx = align == 0 ? x : align == 1 ? x - total * 0.5f : x - total;
        for (int i = 0; i < n; i++) {
            float cw = cellFor(buf[i]) * size;
            c.drawText(buf, i, 1, sx + cw * 0.5f, y, pNum);
            sx += cw;
        }
    }

    private int dashes() { buf[0] = '-'; buf[1] = '-'; return 2; }

    /** format into the shared char[]; allocates nothing, so onDraw may call it freely */
    private int fmt(float value, int dec) {
        long unit = 1;
        for (int i = 0; i < dec; i++) unit *= 10;
        long sc = Math.round((double) value * unit);
        boolean neg = sc < 0;
        if (neg) sc = -sc;
        int end = buf.length;
        for (int i = 0; i < dec; i++) { buf[--end] = (char) ('0' + (sc % 10)); sc /= 10; }
        if (dec > 0) buf[--end] = '.';
        if (sc == 0) buf[--end] = '0';
        else while (sc > 0) { buf[--end] = (char) ('0' + (sc % 10)); sc /= 10; }
        if (neg) buf[--end] = '-';
        int n = buf.length - end;
        System.arraycopy(buf, end, buf, 0, n);
        return n;
    }
}
