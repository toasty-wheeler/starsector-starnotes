package starnotes;

import org.lazywizard.lazylib.ui.LazyFont;

import java.awt.Color;
import java.util.ArrayList;

/**
 * Text drawn straight onto the map with LazyLib's bitmap fonts. Kept in its own class so that if
 * LazyLib is missing, only this class fails to load and the map simply shows no text.
 */
public class MapLabels {

    public static final int HUGE = 0;   // region names (the Abyss)
    public static final int BIG = 1;    // constellation names
    public static final int MED = 2;    // star system names
    public static final int SMALL = 3;  // grid labels
    public static final int TEXT_S = 4; // user text, four sizes
    public static final int TEXT_M = 5;
    public static final int TEXT_L = 6;
    public static final int TEXT_XL = 7;

    private static final String[] FONT_PATHS = {
            "graphics/fonts/orbitron24aabold.fnt",
            "graphics/fonts/orbitron10.fnt",      // constellations: half the old 20px
            "graphics/fonts/victor10.fnt",        // star systems
            "graphics/fonts/victor10.fnt",
            "graphics/fonts/insignia15LTaa.fnt",
            "graphics/fonts/insignia21LTaa.fnt",
            "graphics/fonts/insignia25LTaa.fnt",
            "graphics/fonts/insignia42LTaa.fnt"
    };

    private final LazyFont[] fonts = new LazyFont[FONT_PATHS.length];
    private final ArrayList<LazyFont.DrawableString> strings = new ArrayList<LazyFont.DrawableString>();
    private final ArrayList<Color> colors = new ArrayList<Color>();
    private final ArrayList<Integer> shownAlpha = new ArrayList<Integer>();
    private final ArrayList<Integer> kinds = new ArrayList<Integer>();

    public MapLabels() throws Exception {
        for (int i = 0; i < FONT_PATHS.length; i++) {
            fonts[i] = LazyFont.loadFont(FONT_PATHS[i]);
        }
    }

    /** Creates a label (centered on its point, or anchored at its top-left corner) and returns its id. */
    public int add(String text, int kind, Color color, boolean centered) {
        LazyFont f = fonts[kind];
        LazyFont.DrawableString s = f.createText(text, color, f.getBaseHeight());
        s.setAnchor(centered ? LazyFont.TextAnchor.CENTER : LazyFont.TextAnchor.TOP_LEFT);
        strings.add(s);
        colors.add(color);
        shownAlpha.add(Integer.valueOf(255));
        kinds.add(Integer.valueOf(kind));
        return strings.size() - 1;
    }

    public int add(String text, int kind, Color color) {
        return add(text, kind, color, true);
    }

    public void setText(int id, String text) {
        strings.get(id).setText(text);
    }

    public float width(int id) {
        return strings.get(id).getWidth();
    }

    public float height(int id) {
        return strings.get(id).getHeight();
    }

    /** Width of a single line of text in a given font. */
    public float measure(int kind, String text) {
        if (text == null || text.length() == 0) return 0f;
        return fonts[kind].calcWidth(text, fonts[kind].getBaseHeight());
    }

    public float lineHeight(int kind) {
        return fonts[kind].getBaseHeight();
    }

    public void draw(int id, float x, float y, float alpha) {
        if (alpha <= 0.01f) return;
        LazyFont.DrawableString s = strings.get(id);
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 24f) * 255 / 24; // few steps: avoids rebuilds
        if (a != shownAlpha.get(id).intValue()) {
            Color c = colors.get(id);
            s.setBaseColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
            shownAlpha.set(id, Integer.valueOf(a));
        }
        s.draw(Math.round(x), Math.round(y));
    }
}
