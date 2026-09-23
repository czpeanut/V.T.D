import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Turn a line drawing into assets/car.png for the driving screen.
 *
 * A drawing usually arrives the way a drawing is normally saved: light strokes on an opaque
 * dark background, often as a JPEG. Dropped straight into assets/ that background becomes a
 * black slab that hides the pool of light, the arcs and the starfield behind the car. This
 * converts it instead of asking for the file to be redrawn:
 *
 *   - brightness becomes opacity, so the strokes keep their anti-aliasing and the background
 *     simply disappears;
 *   - a floor is subtracted first, because JPEG leaves a haze of low-level noise across black
 *     areas that would otherwise show up as a dirty rectangle;
 *   - the strokes are tinted to the screen's cyan, with the brightest cores kept near white so
 *     the line work still reads as line work under the glow;
 *   - the result is cropped to its content and scaled down, so the app is not holding margins
 *     and pixels it will never draw.
 *
 *   javac -d build/tools tools/MakeCarAsset.java
 *   java -cp build/tools MakeCarAsset <source-image> [assets/car.png]
 */
public class MakeCarAsset {

    private static final int TINT = 0x3FD2FF;     // the screen's cyan
    private static final int NOISE_FLOOR = 22;    // JPEG haze below this is background
    private static final int CORE = 170;          // above this, fade the tint toward white
    private static final int MAX_EDGE = 480;      // drawn into a box ~190x206; this is ample

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: MakeCarAsset <source-image> [out.png]");
            System.exit(2);
        }
        File out = new File(args.length > 1 ? args[1] : "assets/car.png");
        BufferedImage src = ImageIO.read(new File(args[0]));
        if (src == null) { System.err.println("cannot read " + args[0]); System.exit(2); }
        int w = src.getWidth(), h = src.getHeight();

        int[] luma = new int[w * h];
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int l = (299 * ((p >> 16) & 255) + 587 * ((p >> 8) & 255) + 114 * (p & 255)) / 1000;
                luma[y * w + x] = l;
                if (l > NOISE_FLOOR + 14) {                  // content, not haze
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) { System.err.println("image is blank"); System.exit(2); }
        int pad = Math.max(2, Math.min(w, h) / 100);
        minX = Math.max(0, minX - pad); minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad); maxY = Math.min(h - 1, maxY + pad);
        int cw = maxX - minX + 1, ch = maxY - minY + 1;

        BufferedImage cut = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        int tr = (TINT >> 16) & 255, tg = (TINT >> 8) & 255, tb = TINT & 255;
        int kept = 0;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int l = luma[(y + minY) * w + (x + minX)];
                int a = l - NOISE_FLOOR;
                if (a <= 0) continue;                        // fully transparent background
                a = a * 255 / (255 - NOISE_FLOOR);
                if (a > 255) a = 255;
                // brightest parts of a stroke keep a near-white core under the glow
                int mix = l <= CORE ? 0 : (l - CORE) * 255 / (255 - CORE);
                int r = tr + (255 - tr) * mix / 255;
                int g = tg + (255 - tg) * mix / 255;
                int b = tb + (255 - tb) * mix / 255;
                cut.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                kept++;
            }
        }

        BufferedImage fin = cut;
        if (Math.max(cw, ch) > MAX_EDGE) {
            float s = MAX_EDGE / (float) Math.max(cw, ch);
            int nw = Math.round(cw * s), nh = Math.round(ch * s);
            fin = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = fin.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.drawImage(cut.getScaledInstance(nw, nh, Image.SCALE_SMOOTH), 0, 0, null);
            g2.dispose();
        }
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(fin, "png", out);
        System.out.println("source " + w + "x" + h + "  ->  cropped " + cw + "x" + ch
                + "  ->  " + fin.getWidth() + "x" + fin.getHeight()
                + "   (" + kept + " visible pixels)  ->  " + out.getPath());
    }
}
