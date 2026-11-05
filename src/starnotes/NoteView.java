package starnotes;

import org.lazywizard.lazylib.ui.LazyFont;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;

/**
 * Draws a NoteEditor with LazyLib fonts: a large title line, normal body text, selection highlight and
 * a caret. Kept separate so that a missing LazyLib only disables this class.
 */
public class NoteView implements NoteEditor.Metrics {

    private static final String TITLE_FONT = "graphics/fonts/insignia25LTaa.fnt";
    private static final String BODY_FONT = "graphics/fonts/insignia15LTaa.fnt";

    private final LazyFont titleFont;
    private final LazyFont bodyFont;
    private final float titleSize;
    private final float bodySize;

    private final ArrayList<LazyFont.DrawableString> drawn = new ArrayList<LazyFont.DrawableString>();
    private final ArrayList<float[]> lineInfo = new ArrayList<float[]>(); // xOffset, boxed(0/1), checked(0/1), boxHere(0/1)
    private int builtVersion = -1;
    private int builtLines = -1;
    private LazyFont.DrawableString placeholder;

    public NoteView() throws Exception {
        titleFont = LazyFont.loadFont(TITLE_FONT);
        bodyFont = LazyFont.loadFont(BODY_FONT);
        titleSize = titleFont.getBaseHeight();
        bodySize = bodyFont.getBaseHeight();
    }

    public float width(String s, boolean title) {
        if (s.length() == 0) return 0f;
        return title ? titleFont.calcWidth(s, titleSize) : bodyFont.calcWidth(s, bodySize);
    }

    public float lineHeight(boolean title) {
        return title ? titleSize + 6f : bodySize + 4f;
    }

    private void rebuild(NoteEditor ed, Color titleColor, Color bodyColor) {
        for (int i = 0; i < drawn.size(); i++) {
            try {
                drawn.get(i).dispose();
            } catch (Throwable t) {
                // ignore
            }
        }
        drawn.clear();
        lineInfo.clear();
        String text = ed.getText();
        ArrayList<NoteEditor.VLine> lines = ed.getLines();
        Color doneColor = new Color(bodyColor.getRed() / 2, bodyColor.getGreen() / 2, bodyColor.getBlue() / 2);
        for (int i = 0; i < lines.size(); i++) {
            NoteEditor.VLine l = lines.get(i);
            int ls = ed.logicalStart(l.start);
            boolean boxed = !l.title && ed.hasBox(ls);
            boolean checked = boxed && ed.isChecked(ls);
            boolean boxHere = boxed && l.start == ls;
            // the "[ ] " characters stay in the text; a drawn box takes their place on screen
            int from = boxHere ? Math.min(l.end, l.start + NoteEditor.BOX_OFF.length()) : l.start;
            String s = text.substring(from, l.end);
            float xoff = boxHere ? width(NoteEditor.BOX_OFF, false) : 0f;
            LazyFont f = l.title ? titleFont : bodyFont;
            Color c = l.title ? titleColor : (checked ? doneColor : bodyColor);
            LazyFont.DrawableString d = f.createText(s, c, l.title ? titleSize : bodySize);
            d.setAnchor(LazyFont.TextAnchor.TOP_LEFT);
            drawn.add(d);
            lineInfo.add(new float[]{xoff, boxed ? 1f : 0f, checked ? 1f : 0f, boxHere ? 1f : 0f});
        }
        builtVersion = ed.getVersion();
        builtLines = lines.size();
    }

    /**
     * Draws the editor with its top-left content corner at (left, top) in screen coordinates (y up).
     * The caller sets up the scissor rectangle.
     */
    public void render(NoteEditor ed, float left, float top, float width, float height, float alpha,
                       boolean caretOn, boolean focused, Color titleColor, Color bodyColor, Color selColor, Color hintColor) {
        ArrayList<NoteEditor.VLine> lines = ed.getLines();
        if (builtVersion != ed.getVersion() || builtLines != lines.size()) rebuild(ed, titleColor, bodyColor);
        float scroll = ed.getScroll();
        String text = ed.getText();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // title underline
        if (!lines.isEmpty()) {
            float ty = top + scroll;
            float lastTitleBottom = 0f;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).title) lastTitleBottom = lines.get(i).y + lines.get(i).h;
            }
            float uy = ty - lastTitleBottom - NoteEditor.TITLE_GAP * 0.5f;
            if (uy < top && uy > top - height) {
                GL11.glColor4f(titleColor.getRed() / 255f, titleColor.getGreen() / 255f, titleColor.getBlue() / 255f, 0.35f * alpha);
                GL11.glLineWidth(1f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(left, uy);
                GL11.glVertex2f(left + width, uy);
                GL11.glEnd();
            }
        }

        // selection
        if (ed.hasSelection()) {
            int a = ed.selMin(), b = ed.selMax();
            GL11.glColor4f(selColor.getRed() / 255f, selColor.getGreen() / 255f, selColor.getBlue() / 255f, 0.45f * alpha);
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < lines.size(); i++) {
                NoteEditor.VLine l = lines.get(i);
                if (b < l.start || a > l.end) continue;
                float y = top - (l.y - scroll);
                if (y - l.h > top || y < top - height) continue;
                int s = Math.max(a, l.start), e = Math.min(b, l.end);
                float x1 = left + width(text.substring(l.start, s), l.title);
                float x2 = left + width(text.substring(l.start, e), l.title);
                if (b > l.end) x2 += 5f; // selection continues past the line break
                if (x2 <= x1) continue;
                GL11.glVertex2f(x1, y - l.h);
                GL11.glVertex2f(x2, y - l.h);
                GL11.glVertex2f(x2, y);
                GL11.glVertex2f(x1, y);
            }
            GL11.glEnd();
        }

        // text
        for (int i = 0; i < lines.size() && i < drawn.size(); i++) {
            NoteEditor.VLine l = lines.get(i);
            float y = top - (l.y - scroll);
            if (y - l.h > top + 4f || y < top - height - 4f) continue;
            LazyFont.DrawableString d = drawn.get(i);
            d.draw(Math.round(left + lineInfo.get(i)[0]), Math.round(y - (l.title ? 3f : 2f)));
        }

        // placeholder for an empty note
        if (text.length() == 0) {
            if (placeholder == null) {
                placeholder = titleFont.createText("Type a title...", hintColor, titleSize);
                placeholder.setAnchor(LazyFont.TextAnchor.TOP_LEFT);
            }
            placeholder.draw(Math.round(left + 3f), Math.round(top - 3f));
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // checkboxes and strike-through for ticked lines
        for (int i = 0; i < lines.size() && i < lineInfo.size(); i++) {
            float[] info = lineInfo.get(i);
            if (info[1] == 0f) continue;
            NoteEditor.VLine l = lines.get(i);
            float y = top - (l.y - scroll);
            if (y - l.h > top + 4f || y < top - height - 4f) continue;
            float mid = y - l.h * 0.5f;
            boolean checked = info[2] == 1f;
            if (info[3] == 1f) {
                float bs = bodySize * 0.72f;
                float bx = left + 1f;
                GL11.glColor4f(bodyColor.getRed() / 255f, bodyColor.getGreen() / 255f, bodyColor.getBlue() / 255f, (checked ? 0.55f : 0.9f) * alpha);
                GL11.glLineWidth(1.5f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(bx, mid - bs * 0.5f);
                GL11.glVertex2f(bx + bs, mid - bs * 0.5f);
                GL11.glVertex2f(bx + bs, mid + bs * 0.5f);
                GL11.glVertex2f(bx, mid + bs * 0.5f);
                GL11.glEnd();
                if (checked) {
                    GL11.glColor4f(titleColor.getRed() / 255f, titleColor.getGreen() / 255f, titleColor.getBlue() / 255f, alpha);
                    GL11.glLineWidth(2.2f);
                    GL11.glBegin(GL11.GL_LINE_STRIP);
                    GL11.glVertex2f(bx + bs * 0.18f, mid);
                    GL11.glVertex2f(bx + bs * 0.42f, mid - bs * 0.3f);
                    GL11.glVertex2f(bx + bs * 1.05f, mid + bs * 0.55f);
                    GL11.glEnd();
                }
            }
            if (checked) {
                float x0 = left + info[0];
                float x1 = left + info[0] + drawn.get(i).getWidth();
                if (x1 > x0 + 1f) {
                    GL11.glColor4f(bodyColor.getRed() / 255f, bodyColor.getGreen() / 255f, bodyColor.getBlue() / 255f, 0.6f * alpha);
                    GL11.glLineWidth(1.2f);
                    GL11.glBegin(GL11.GL_LINES);
                    GL11.glVertex2f(x0, mid + 1f);
                    GL11.glVertex2f(x1, mid + 1f);
                    GL11.glEnd();
                }
            }
        }

        // caret
        if (focused && caretOn && !lines.isEmpty()) {
            NoteEditor.VLine l = ed.caretLine();
            float y = top - (l.y - scroll);
            float x = left + ed.xOf(ed.getCaret());
            GL11.glColor4f(bodyColor.getRed() / 255f, bodyColor.getGreen() / 255f, bodyColor.getBlue() / 255f, alpha);
            GL11.glLineWidth(l.title ? 2f : 1.5f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(x + 1f, y - 1f);
            GL11.glVertex2f(x + 1f, y - l.h + 2f);
            GL11.glEnd();
        }
    }
}
