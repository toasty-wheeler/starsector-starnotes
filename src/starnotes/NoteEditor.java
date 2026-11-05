package starnotes;

import java.util.ArrayList;

/**
 * Text-editing model for a note: caret, selection, word wrap, scrolling. No rendering and no game
 * classes, so it can be tested on its own. The first line of the text is the title and is measured
 * with the title font.
 */
public class NoteEditor {

    /** Text measurement supplied by whoever draws the editor. */
    public interface Metrics {
        float width(String s, boolean title);

        float lineHeight(boolean title);
    }

    /** One visual (wrapped) line: text[start, end), y = top offset from the content top. */
    public static class VLine {
        public int start;
        public int end;
        public boolean title;
        public float y;
        public float h;
    }

    public static final float TITLE_GAP = 8f;

    private final Metrics m;
    private String text = "";
    private int caret = 0;
    private int anchor = 0;
    private float wrapW = 400f;
    private float viewH = 300f;
    private float scroll = 0f;
    private float preferredX = -1f;
    private int maxChars = 6000;

    private final ArrayList<VLine> lines = new ArrayList<VLine>();
    private boolean layoutDirty = true;
    private float contentH = 0f;
    private int version = 0; // bumps on every text change

    public NoteEditor(Metrics m) {
        this.m = m;
    }

    // ------------------------------------------------------------------ state

    public String getText() {
        return text;
    }

    public void setText(String t) {
        text = clean(t == null ? "" : t);
        if (text.length() > maxChars) text = text.substring(0, maxChars);
        caret = text.length();
        anchor = caret;
        preferredX = -1f;
        layoutDirty = true;
        version++;
    }

    public int getVersion() {
        return version;
    }

    public int getCaret() {
        return caret;
    }

    public int getAnchor() {
        return anchor;
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    public int selMin() {
        return Math.min(caret, anchor);
    }

    public int selMax() {
        return Math.max(caret, anchor);
    }

    public String selectedText() {
        return text.substring(selMin(), selMax());
    }

    public void setSize(float wrapWidth, float viewHeight) {
        if (Math.abs(wrapWidth - wrapW) > 0.5f) layoutDirty = true;
        wrapW = Math.max(40f, wrapWidth);
        viewH = Math.max(20f, viewHeight);
    }

    public float getScroll() {
        return scroll;
    }

    public ArrayList<VLine> getLines() {
        layout();
        return lines;
    }

    private static String clean(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
    }

    // ------------------------------------------------------------------ layout

    public void layout() {
        if (!layoutDirty) return;
        layoutDirty = false;
        lines.clear();
        float y = 0f;
        int start = 0;
        int len = text.length();
        while (true) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? len : nl;
            boolean title = start == 0;
            y = wrap(start, end, title, y);
            if (title) y += TITLE_GAP;
            if (nl < 0) break;
            start = nl + 1;
        }
        contentH = y;
    }

    private float wrap(int s, int e, boolean title, float y) {
        float h = m.lineHeight(title);
        if (s == e) {
            addLine(s, e, title, y, h);
            return y + h;
        }
        int ls = s;
        while (ls < e) {
            int le = fit(ls, e, title);
            if (le <= ls) le = ls + 1;
            if (le < e) {
                int sp = text.lastIndexOf(' ', le - 1);
                if (sp >= ls && sp + 1 > ls && sp + 1 <= le) le = sp + 1; // break after a space
            }
            addLine(ls, le, title, y, h);
            y += h;
            ls = le;
        }
        return y;
    }

    /** Largest k in (ls, e] whose text[ls, k) fits in the wrap width. */
    private int fit(int ls, int e, boolean title) {
        if (m.width(text.substring(ls, e), title) <= wrapW) return e;
        int lo = ls + 1, hi = e, best = ls + 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (m.width(text.substring(ls, mid), title) <= wrapW) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private void addLine(int s, int e, boolean title, float y, float h) {
        VLine l = new VLine();
        l.start = s;
        l.end = e;
        l.title = title;
        l.y = y;
        l.h = h;
        lines.add(l);
    }

    public int lineOf(int pos) {
        layout();
        for (int i = 0; i < lines.size(); i++) {
            VLine l = lines.get(i);
            if (pos >= l.start && pos < l.end) return i;
            if (pos == l.end && (l.end == text.length() || text.charAt(l.end) == '\n')) return i;
        }
        return lines.size() - 1;
    }

    /** Caret x within its line, relative to the left edge. */
    public float xOf(int pos) {
        VLine l = lines.get(lineOf(pos));
        int p = Math.max(l.start, Math.min(pos, l.end));
        return m.width(text.substring(l.start, p), l.title);
    }

    public VLine caretLine() {
        return lines.get(lineOf(caret));
    }

    /** Text index nearest to a point in content coordinates (x from left, y down from content top). */
    public int indexAt(float x, float y) {
        layout();
        if (lines.isEmpty()) return 0;
        VLine line = lines.get(lines.size() - 1);
        for (int i = 0; i < lines.size(); i++) {
            VLine l = lines.get(i);
            if (y < l.y + l.h) {
                line = l;
                break;
            }
        }
        return indexInLine(line, x);
    }

    private int indexInLine(VLine l, float x) {
        int lastPos = l.end;
        // on a wrapped line the trailing space belongs to the line, but the caret goes before it
        if (l.end > l.start && l.end < text.length() && text.charAt(l.end) != '\n' && text.charAt(l.end - 1) == ' ') {
            lastPos = l.end - 1;
        }
        int best = l.start;
        float bestD = Float.MAX_VALUE;
        for (int k = l.start; k <= lastPos; k++) {
            float d = Math.abs(m.width(text.substring(l.start, k), l.title) - x);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ editing

    private void changed() {
        layoutDirty = true;
        version++;
        preferredX = -1f;
        ensureCaretVisible();
    }

    public void insert(String s) {
        s = clean(s);
        if (hasSelection()) deleteRange(selMin(), selMax());
        int room = maxChars - text.length();
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        text = text.substring(0, caret) + s + text.substring(caret);
        caret += s.length();
        anchor = caret;
        changed();
    }

    private void deleteRange(int a, int b) {
        if (b <= a) return;
        text = text.substring(0, a) + text.substring(b);
        caret = a;
        anchor = a;
    }

    public void backspace(boolean word) {
        if (hasSelection()) {
            deleteRange(selMin(), selMax());
        } else if (caret > 0) {
            int k = word ? prevWord(caret) : caret - 1;
            deleteRange(k, caret);
        } else {
            return;
        }
        changed();
    }

    public void deleteForward(boolean word) {
        if (hasSelection()) {
            deleteRange(selMin(), selMax());
        } else if (caret < text.length()) {
            int k = word ? nextWord(caret) : caret + 1;
            deleteRange(caret, k);
        } else {
            return;
        }
        changed();
    }

    public String cut() {
        if (!hasSelection()) return null;
        String s = selectedText();
        deleteRange(selMin(), selMax());
        changed();
        return s;
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
        preferredX = -1f;
    }

    public void selectWordAt(int pos) {
        pos = Math.max(0, Math.min(text.length(), pos));
        int a = pos, b = pos;
        while (a > 0 && isWordChar(text.charAt(a - 1))) a--;
        while (b < text.length() && isWordChar(text.charAt(b))) b++;
        anchor = a;
        caret = b;
        preferredX = -1f;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'';
    }

    private int prevWord(int pos) {
        int k = pos;
        while (k > 0 && !isWordChar(text.charAt(k - 1)) && text.charAt(k - 1) != '\n') k--;
        while (k > 0 && isWordChar(text.charAt(k - 1))) k--;
        if (k == pos && k > 0) k--; // always remove at least one character
        return k;
    }

    private int nextWord(int pos) {
        int k = pos;
        int n = text.length();
        while (k < n && !isWordChar(text.charAt(k)) && text.charAt(k) != '\n') k++;
        while (k < n && isWordChar(text.charAt(k))) k++;
        if (k == pos && k < n) k++;
        return k;
    }

    // ------------------------------------------------------------------ checkboxes

    public static final String BOX_OFF = "[ ] ";
    public static final String BOX_ON = "[x] ";

    public int logicalStart(int pos) {
        return pos <= 0 ? 0 : text.lastIndexOf('\n', pos - 1) + 1;
    }

    /** True if the logical line starting at 'start' begins with a checkbox (the title never does). */
    public boolean hasBox(int start) {
        return start > 0 && (text.startsWith(BOX_OFF, start) || text.startsWith(BOX_ON, start) || text.startsWith("[X] ", start));
    }

    public boolean isChecked(int start) {
        return start > 0 && (text.startsWith(BOX_ON, start) || text.startsWith("[X] ", start));
    }

    /**
     * Adds an unticked checkbox to every selected line below the title (or the caret's line). If they
     * all have one already, removes them instead. Returns false if no line qualified.
     */
    public boolean toggleCheckboxes() {
        int a = hasSelection() ? selMin() : caret;
        int b = hasSelection() ? selMax() : caret;
        ArrayList<Integer> starts = new ArrayList<Integer>();
        int ls = logicalStart(a);
        while (true) {
            if (ls > 0) starts.add(Integer.valueOf(ls));
            int nl = text.indexOf('\n', ls);
            if (nl < 0 || nl + 1 > b || (nl + 1 == b && b > a)) break;
            ls = nl + 1;
        }
        if (starts.isEmpty()) return false;
        boolean allBoxed = true;
        for (int i = 0; i < starts.size(); i++) {
            if (!hasBox(starts.get(i).intValue())) allBoxed = false;
        }
        for (int i = starts.size() - 1; i >= 0; i--) {
            int p = starts.get(i).intValue();
            if (allBoxed) {
                text = text.substring(0, p) + text.substring(p + 4);
                caret = shiftRemove(caret, p);
                anchor = shiftRemove(anchor, p);
            } else if (!hasBox(p)) {
                if (text.length() + 4 > maxChars) break;
                text = text.substring(0, p) + BOX_OFF + text.substring(p);
                if (caret >= p) caret += 4;
                if (anchor >= p) anchor += 4;
            }
        }
        changed();
        return true;
    }

    private static int shiftRemove(int pos, int p) {
        if (pos >= p + 4) return pos - 4;
        if (pos > p) return p;
        return pos;
    }

    /** Ticks or unticks the checkbox of a logical line. */
    public void toggleCheck(int start) {
        if (!hasBox(start)) return;
        char c = isChecked(start) ? ' ' : 'x';
        text = text.substring(0, start + 1) + c + text.substring(start + 2);
        layoutDirty = true;
        version++;
    }

    /** If (x, y) in view coordinates is on a checkbox, toggles it and returns true. */
    public boolean checkHit(float x, float y) {
        layout();
        float cy = y + scroll;
        for (int i = 0; i < lines.size(); i++) {
            VLine l = lines.get(i);
            if (cy < l.y || cy >= l.y + l.h) continue;
            if (l.title || l.start != logicalStart(l.start) || !hasBox(l.start)) return false;
            if (x >= -6f && x <= m.width(BOX_OFF, false) + 2f) {
                toggleCheck(l.start);
                return true;
            }
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------ caret movement

    public static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3, HOME = 4, END = 5;

    public void move(int dir, boolean select, boolean word) {
        layout();
        int pos = caret;
        boolean keepX = false;
        if (!select && hasSelection() && (dir == LEFT || dir == RIGHT) && !word) {
            pos = dir == LEFT ? selMin() : selMax();
        } else if (dir == LEFT) {
            pos = word ? prevWord(caret) : Math.max(0, caret - 1);
        } else if (dir == RIGHT) {
            pos = word ? nextWord(caret) : Math.min(text.length(), caret + 1);
        } else if (dir == UP || dir == DOWN) {
            int li = lineOf(caret);
            if (preferredX < 0f) preferredX = xOf(caret);
            int target = li + (dir == UP ? -1 : 1);
            if (target < 0) {
                pos = 0;
            } else if (target >= lines.size()) {
                pos = text.length();
            } else {
                pos = indexInLine(lines.get(target), preferredX);
            }
            keepX = true;
        } else if (dir == HOME) {
            pos = word ? 0 : lines.get(lineOf(caret)).start;
        } else if (dir == END) {
            if (word) {
                pos = text.length();
            } else {
                VLine l = lines.get(lineOf(caret));
                pos = l.end;
                if (l.end < text.length() && text.charAt(l.end) != '\n' && l.end > l.start && text.charAt(l.end - 1) == ' ') {
                    pos = l.end - 1;
                }
            }
        }
        caret = pos;
        if (!select) anchor = pos;
        if (!keepX) preferredX = -1f;
        ensureCaretVisible();
    }

    // ------------------------------------------------------------------ mouse and scrolling

    public void mouseDown(float x, float y, boolean extend) {
        int pos = indexAt(x, y + scroll);
        caret = pos;
        if (!extend) anchor = pos;
        preferredX = -1f;
    }

    public void mouseDrag(float x, float y) {
        caret = indexAt(x, y + scroll);
        preferredX = -1f;
        ensureCaretVisible();
    }

    public void doubleClick(float x, float y) {
        selectWordAt(indexAt(x, y + scroll));
    }

    public void scrollBy(float dy) {
        layout();
        scroll = Math.max(0f, Math.min(Math.max(0f, contentH - viewH + 6f), scroll + dy));
    }

    public void ensureCaretVisible() {
        layout();
        if (lines.isEmpty()) return;
        VLine l = caretLine();
        if (l.y < scroll) scroll = l.y;
        if (l.y + l.h > scroll + viewH) scroll = l.y + l.h - viewH;
        scroll = Math.max(0f, Math.min(Math.max(0f, contentH - viewH + 6f), scroll));
    }
}
