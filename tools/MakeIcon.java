import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generate res/drawable-nodpi/ic_launcher.png: the V.T.D. arrow.
 *
 * Kept as a generator rather than a checked-in drawing so the mark stays tied to the screen it
 * belongs to -- same cyan, same two-pass glow, same chamfer as the panels -- and can be
 * regenerated at a different size without redrawing it by hand.
 *
 *   javac -d build/tools tools/MakeIcon.java
 *   java -cp build/tools MakeIcon [size] [out.png]
 */
public class MakeIcon {

    private static final Color CYAN = new Color(0x3F, 0xD2, 0xFF);
    private static final Color BODY = new Color(0x07, 0x0C, 0x14);

    public static void main(String[] args) throws Exception {
        int s = args.length > 0 ? Integer.parseInt(args[0]) : 96;
        File out = new File(args.length > 1 ? args[1] : "res/drawable-nodpi/ic_launcher.png");

        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // plate: rounded, with the top-left corner chamfered the way the panels are
        float pad = s * 0.055f, ch = s * 0.20f, rad = s * 0.17f;
        float l = pad, t = pad, r = s - pad, b = s - pad;
        GeneralPath plate = new GeneralPath();
        plate.moveTo(l + ch, t);
        plate.lineTo(r - rad, t);
        plate.quadTo(r, t, r, t + rad);
        plate.lineTo(r, b - rad);
        plate.quadTo(r, b, r - rad, b);
        plate.lineTo(l + rad, b);
        plate.quadTo(l, b, l, b - rad);
        plate.lineTo(l, t + ch);
        plate.closePath();

        g.setColor(BODY);
        g.fill(plate);
        g.setColor(new Color(0x3F, 0xD2, 0xFF, 0x3A));
        g.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(plate);
        g.setColor(new Color(0x3F, 0xD2, 0xFF, 0xCC));
        g.setStroke(new BasicStroke(s * 0.022f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(plate);

        // the arrow: a head over a shaft, weighted so it still reads at launcher size
        float cx = s * 0.5f;
        float headTop = s * 0.215f, headBot = s * 0.545f;
        float hw = s * 0.245f, sw = s * 0.095f;
        GeneralPath arrow = new GeneralPath();
        arrow.moveTo(cx, headTop);
        arrow.lineTo(cx + hw, headBot);
        arrow.lineTo(cx + sw, headBot);
        arrow.lineTo(cx + sw, s * 0.800f);
        arrow.lineTo(cx - sw, s * 0.800f);
        arrow.lineTo(cx - sw, headBot);
        arrow.lineTo(cx - hw, headBot);
        arrow.closePath();

        // two-pass glow, then the solid mark, exactly as the dash draws its own arrow
        g.setColor(new Color(0x3F, 0xD2, 0xFF, 0x33));
        g.setStroke(new BasicStroke(s * 0.115f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);
        g.setColor(new Color(0x3F, 0xD2, 0xFF, 0x66));
        g.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);
        g.setColor(CYAN);
        g.fill(arrow);
        g.setColor(new Color(0xEA, 0xF6, 0xFF));
        g.setStroke(new BasicStroke(s * 0.016f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);

        g.dispose();
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getPath() + "  " + s + "x" + s);
    }
}
