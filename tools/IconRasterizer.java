import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build-time helper: rasterizes the pixel-art SVG icons (24x24 viewBox, purely
 * rectilinear paths, fill="currentColor") into white-on-transparent PNGs, which
 * ClickGuiScreen blits with a color tint (so one PNG serves the dim/hover/active
 * states). JDK-only (java.awt) — no external rasterizer needed.
 *
 * The generated PNGs under
 * {@code src/client/resources/assets/unlucky/textures/gui/icons/} are the shipped
 * artifact and are committed; this tool only needs re-running when an icon
 * changes or a new one is added.
 *
 * To add an icon: paste its SVG path 'd' attribute into ICONS below, run
 *
 * <pre>
 *   javac -d /tmp/icons tools/IconRasterizer.java
 *   java -cp /tmp/icons -Djava.awt.headless=true IconRasterizer \
 *        src/client/resources/assets/unlucky/textures/gui/icons
 * </pre>
 *
 * then reference it from ClickGuiScreen via {@code icon("name")}.
 *
 * Sizing note: icons are drawn at >= 12 GUI px. Minecraft samples GUI textures
 * nearest-neighbour, so a stroke is only ever dropped if the draw size falls
 * below 12 — the thinnest feature in these icons is 2 of the 24 grid units,
 * i.e. 1/12 of the icon. Keep TAB_ICON / TB_ICON in ClickGuiScreen >= 12.
 */
public class IconRasterizer {

    static final int SIZE = 64; // output px; icons are drawn small + tinted in-game

    // filename (no ext) -> SVG path 'd' attribute, exactly as provided
    static final Map<String, String> ICONS = new LinkedHashMap<>();
    static {
        ICONS.put("search", "M22 22h-2v-2h2v2Zm-2-2h-2v-2h2v2Zm-6-2H6v-2h8v2Zm4 0h-2v-2h2v2ZM6 16H4v-2h2v2Zm10 0h-2v-2h2v2ZM4 14H2V6h2v8Zm14 0h-2V6h2v8ZM6 6H4V4h2v2Zm10 0h-2V4h2v2Zm-2-2H6V2h8v2Z");
        ICONS.put("combat", "M13 22h-2v-4H7v-2h2V4h2v12h2V4h2v12h2v2h-4v4Zm0-18h-2V2h2v2Z");
        ICONS.put("player", "M6 22H4v-4h2v4Zm14 0h-2v-4h2v4ZM8 18H6v-2h2v2Zm10 0h-2v-2h2v2Zm-2-2H8v-2h8v2Zm-1-4H9v-2h6v2Zm-6-2H7V4h2v6Zm8 0h-2V4h2v6Zm-2-6H9V2h6v2Z");
        ICONS.put("movement", "M20 22H6V20H20V22ZM6 20H4V12H6V20ZM20 16H22V20H20V18H14V16H18V14H20V16ZM12 18H10V16H12V18ZM10 16H8V8H4V6H10V16ZM14 16H12V9H14V16ZM22 14H20V6H22V14ZM4 12H2V8H4V12ZM18 12H16V9H18V12ZM16 9H14V7H16V9ZM12 6H10V4H12V6ZM20 6H18V4H20V6ZM4 4H2V2H4V4ZM8 4H6V2H8V4ZM18 4H12V2H18V4Z");
        ICONS.put("render", "M16 20H8v-2h8v2Zm-8-2H4v-2h4v2Zm12 0h-4v-2h4v2ZM4 16H2v-2h2v2Zm10-6h-2v2h2v-2h2v4h-2v2h-4v-2H8v-4h2V8h4v2Zm8 6h-2v-2h2v2ZM2 14H0v-4h2v4Zm22 0h-2v-4h2v4ZM4 10H2V8h2v2Zm18 0h-2V8h2v2ZM8 8H4V6h4v2Zm12 0h-4V6h4v2Zm-4-2H8V4h8v2Z");
        ICONS.put("world", "M12 20h2v-4h2v4h2v2H6v-2h4v-4h2v4Zm-6 0H4v-2h2v2Zm14 0h-2v-2h2v2ZM4 12h2v2H4v4H2V6h2v6Zm18 6h-2v-2h-4v-2h4v-4h-2V8h2V6h2v12Zm-12-2H6v-2h4v2Zm8-4h-4v-2h4v2Zm-4-2h-4V8h4v2Zm4-6h-8v4H8V4H6V2h12v2ZM6 6H4V4h2v2Zm14 0h-2V4h2v2Z");
        ICONS.put("misc", "M14 22H10V20H14V22ZM10 20H8V18H10V20ZM16 20H14V18H16V20ZM8 18H6V16H8V18ZM18 18H16V16H18V18ZM13 17H11V15H13V17ZM6 16H4V14H6V16ZM20 16H18V14H20V16ZM4 14H2V6H4V14ZM22 14H20V6H22V14ZM10 10H11V13H8V12H6V8H10V10ZM18 12H16V13H13V10H14V8H18V12ZM6 6H4V4H6V6ZM20 6H18V4H20V6ZM18 4H6V2H18V4Z");
        ICONS.put("mouse", "M16 22H8v-2h8v2Zm-8-2H6v-2h2v2Zm10 0h-2v-2h2v2ZM6 18H4V6h2v12Zm14 0h-2V6h2v12Zm-7-8h-2V6h2v4ZM8 6H6V4h2v2Zm10 0h-2V4h2v2Zm-2-2H8V2h8v2Z");
        ICONS.put("hud_editor", "M3 21h2v2H1v-4h2v2Zm6 0H5v-2h4v2Zm-4-2H3v-4h2v4Zm6 0H9v-2h2v2Zm2-2h-2v-2h2v2Zm-6-2H5v-2h2v2Zm8 0h-2v-2h2v2Zm4 0h-2v-2h2v2ZM9 13H7v-2h2v2Zm8 0h-2v-2h2v2Zm4 0h-2v-2h2v2Zm-10-2H9V9h2v2Zm4 0h-2V9h2v2Zm4 0h-2V9h2v2Zm-6-2h-2V7h2v2Zm8 0h-2V7h2v2ZM11 7H9V5h2v2Zm4 0h-2V5h2v2Zm8 0h-2V5h2v2ZM13 5h-2V3h2v2Zm4 0h-2V3h2v2Zm4 0h-2V3h2v2Zm-2-2h-2V1h2v2Z");
        ICONS.put("friends", "M2 22H0v-4h2v4Zm14 0h-2v-4h2v4Zm8 0h-2v-4h2v4ZM4 18H2v-2h2v2Zm10 0h-2v-2h2v2Zm8 0h-2v-2h2v2Zm-10-2H4v-2h8v2Zm8 0h-4v-2h4v2Zm-9-4H5v-2h6v2Zm8 0h-4v-2h4v2ZM5 10H3V4h2v6Zm8 0h-2V4h2v6Zm8 0h-2V4h2v6ZM11 4H5V2h6v2Zm8 0h-4V2h4v2Z");
        ICONS.put("settings", "M4 20h3v-2h4v4h2v-4h4v2h-2v4H9v-4H7v2H2v-5h2v3Zm18 2h-5v-2h3v-3h2v5ZM6 11H2v2h4v4H4v-2H0V9h4V7h2v4Zm14-2h4v6h-4v2h-2v-4h4v-2h-4V7h2v2Zm-6 7h-4v-2h4v2Zm-4-2H8v-4h2v4Zm6 0h-2v-4h2v4Zm-2-4h-4V8h4v2ZM7 4H4v3H2V2h5v2Zm8 0h2V2h5v5h-2V4h-3v2h-4V2h-2v4H7V4h2V0h6v4Z");
        ICONS.put("close", "M7 19H5v-2h2v2Zm12 0h-2v-2h2v2ZM9 15v2H7v-2h2Zm8 2h-2v-2h2v2Zm-6-2H9v-2h2v2Zm4 0h-2v-2h2v2Zm-2-2h-2v-2h2v2Zm-2-2H9V9h2v2Zm4 0h-2V9h2v2ZM9 9H7V7h2v2Zm8 0h-2V7h2v2ZM7 7H5V5h2v2Zm12 0h-2V5h2v2Z");
    }

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        outDir.mkdirs();
        for (Map.Entry<String, String> e : ICONS.entrySet()) {
            Path2D.Double path = parse(e.getValue());
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = SIZE / 24.0;
            g.scale(scale, scale);
            g.setColor(Color.WHITE); // tinted at draw time in-game
            g.fill(path);
            g.dispose();
            File out = new File(outDir, e.getKey() + ".png");
            ImageIO.write(img, "PNG", out);
            System.out.println("wrote " + out.getPath());
        }
    }

    // ---- minimal SVG path parser: supports M m L l H h V v Z z (all these icons use) ----
    static Path2D.Double parse(String d) {
        List<String> tokens = tokenize(d);
        Path2D.Double p = new Path2D.Double(Path2D.WIND_NON_ZERO); // SVG default fill-rule
        double cx = 0, cy = 0, sx = 0, sy = 0;
        int i = 0;
        char cmd = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (isCommand(t)) {
                cmd = t.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.closePath();
                    cx = sx;
                    cy = sy;
                }
                continue;
            }
            // t is a number: apply the current command (with implicit-repeat semantics)
            switch (cmd) {
                case 'M': case 'L': {
                    double x = num(tokens, i++);
                    double y = num(tokens, i++);
                    if (cmd == 'M') { p.moveTo(x, y); sx = x; sy = y; cmd = 'L'; }
                    else p.lineTo(x, y);
                    cx = x; cy = y;
                    break;
                }
                case 'm': case 'l': {
                    double x = cx + num(tokens, i++);
                    double y = cy + num(tokens, i++);
                    if (cmd == 'm') { p.moveTo(x, y); sx = x; sy = y; cmd = 'l'; }
                    else p.lineTo(x, y);
                    cx = x; cy = y;
                    break;
                }
                case 'H': { double x = num(tokens, i++); p.lineTo(x, cy); cx = x; break; }
                case 'h': { double x = cx + num(tokens, i++); p.lineTo(x, cy); cx = x; break; }
                case 'V': { double y = num(tokens, i++); p.lineTo(cx, y); cy = y; break; }
                case 'v': { double y = cy + num(tokens, i++); p.lineTo(cx, y); cy = y; break; }
                default: i++; // shouldn't happen for these icons
            }
        }
        return p;
    }

    static double num(List<String> tokens, int i) {
        return Double.parseDouble(tokens.get(i));
    }

    static boolean isCommand(String t) {
        return t.length() == 1 && Character.isLetter(t.charAt(0));
    }

    static List<String> tokenize(String d) {
        List<String> out = new ArrayList<>();
        int i = 0, n = d.length();
        while (i < n) {
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                out.add(String.valueOf(c));
                i++;
            } else if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                // number: optional sign, digits, optional single '.'
                int j = i;
                if (d.charAt(j) == '-' || d.charAt(j) == '+') j++;
                boolean dot = false;
                while (j < n) {
                    char cj = d.charAt(j);
                    if (Character.isDigit(cj)) { j++; }
                    else if (cj == '.' && !dot) { dot = true; j++; }
                    else break;
                }
                out.add(d.substring(i, j));
                i = j;
            }
        }
        return out;
    }
}
