package starnotes;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignTerrainAPI;
import com.fs.starfarer.api.campaign.CampaignTerrainPlugin;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import com.fs.starfarer.api.impl.campaign.velfield.SlipstreamTerrainPlugin2;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/**
 * The StarNotes screen.
 *
 * Layout (panel-local, origin top-left, y downward):
 *   left strip   : "+ New note", "Map", one tab per note, ("Close" in the full-screen window)
 *   tool column  : icon buttons (pen, eraser, symbols, move, undo, clear); options pop out to the right
 *   map area     : StarNotes' own sector map in an amber CRT style - grid, constellation and system
 *                  names, discovered slipstreams, the Abyss - with the drawing on top
 *
 * Inside the Intel screen the game routes clicks on its ButtonAPI widgets to the intel entry, not to
 * this panel, so every button here is drawn and hit-tested by this class.
 */
public class StarNotesPanel implements CustomUIPanelPlugin {

    private static final String SAVE_KEY = "starnotes_data_v1";
    private static final String NOTES_KEY = "starnotes_notes_v1";

    // ---- layout ----
    private static final float SIDE_W = 150f;
    private static final float CONTENT_X = SIDE_W + 8f;
    private static final float ROW = 28f;
    private static final float TAB_H = 24f;
    private static final float X_ZONE = 24f;
    private static final float ICON = 36f;
    private static final float ICON_GAP = 6f;
    private static final float TOOL_W = ICON + 8f;
    private static final float MAP_X = CONTENT_X + TOOL_W + 6f;
    private static final float CELL = 28f;   // flyout cell size
    private static final float FLY_PAD = 6f;
    private static final float LABEL_DY = 4f;
    private static final float STATUS_H = 24f;
    private static final int MAX_NOTE_CHARS = 6000;

    // ---- map look ----
    private static final float GRID_MINOR = 2000f;   // same cell size as the game's map grid
    private static final float GRID_MAJOR = 10000f;
    private static final Color AMBER = new Color(255, 168, 56);
    private static final Color AMBER_DIM = new Color(178, 104, 34);
    private static final Color AMBER_HOT = new Color(255, 222, 150);
    private static final Color STREAM = new Color(255, 232, 186);
    private static final Color ABYSS = new Color(205, 28, 22);

    // ---- tools ----
    private static final int TOOL_PEN = 0;
    private static final int TOOL_ERASER = 1;
    private static final int TOOL_SYMBOL = 2;
    private static final int TOOL_MOVE = 3;
    private static final int TOOL_TEXT = 4;
    private static final int[] TOOL_ORDER = {0, 1, 2, 4, 3}; // pen, eraser, symbols, text, move

    private static final String[] TOOL_TIPS = {
            "Pen: draw freehand (click again for colors and thickness)",
            "Eraser: drag over a line or symbol to remove it",
            "Symbols: click the map to place one, drag one to move it (click again for shapes and colors)",
            "Move: drag symbols and text around; drag empty space to pan the map",
            "Text: click the map to write, click text to edit or drag it (click again for size and color)"
    };

    // ---- flyouts ----
    private static final int FLY_NONE = 0;
    private static final int FLY_PEN = 1;
    private static final int FLY_SYMBOLS = 2;
    private static final int FLY_CLEAR = 3;
    private static final int FLY_TEXT = 4;

    // ---- icon kinds ----
    private static final int IC_TEXT = -1;
    private static final int IC_PEN = 0;
    private static final int IC_ERASER = 1;
    private static final int IC_SYMBOLS = 2;
    private static final int IC_MOVE = 3;
    private static final int IC_UNDO = 4;
    private static final int IC_CLEAR = 5;
    private static final int IC_SWATCH = 6;
    private static final int IC_SIZE = 7;
    private static final int IC_SYMBOL = 8;
    private static final int IC_CONFIRM = 9;
    private static final int IC_TEXTTOOL = 10;
    private static final int IC_TSIZE = 11;
    private static final int IC_STYLE = 12;
    private static final int IC_CHECKBOX = 13;

    private static final String[] TSIZE_NAMES = {"Small text", "Medium text", "Large text", "Huge text"};
    private static final String[] STYLE_NAMES = {"Solid line", "Dashed line", "Dash-dot line"};
    private static final int MAX_MAP_TEXT = 300;

    private static final String[] COLOR_NAMES = {"Red", "Yellow", "Cyan", "Green", "White"};
    private static final Color[] COLORS = {
            new Color(235, 65, 65),
            new Color(245, 210, 60),
            new Color(80, 205, 255),
            new Color(95, 225, 105),
            new Color(240, 240, 240)
    };
    private static final String[] SIZE_NAMES = {"Thin", "Medium", "Thick"};
    private static final float[] SIZES = {2f, 4f, 8f};
    private static final String[] SYMBOL_NAMES = {"X mark", "Square", "Triangle", "Diamond", "Star", "Ring"};

    // ---- data model ----
    private static class Stroke {
        int color;
        float width;
        int style; // 0 solid, 1 dashed, 2 dash-dot
        ArrayList<float[]> pts = new ArrayList<float[]>();
    }

    /** Text written on the map. (x, y) is its top-left corner in map units; it keeps its size on screen. */
    private static class MapText {
        float x;
        float y;
        int color;
        int size;
        String text = "";
        int labelId = -1;
        String shownText = null;
        int shownColor = -1;
        int shownSize = -1;
    }

    private static class Symbol {
        int type;
        int color;
        float x;
        float y;
    }

    private static class Dot {
        float x;
        float y;
        String name;
        boolean entered;
        int labelId = -1;
    }

    private static class NameTag {
        float x;
        float y;
        int labelId = -1;
    }

    /** A button drawn and hit-tested by this panel. Coordinates are panel-local, top-left origin. */
    private static class Btn {
        String id;
        String text;   // for IC_TEXT buttons
        String tip;    // hover hint shown in the status line
        int icon = IC_TEXT;
        int arg = 0;   // color / size / symbol index for flyout cells
        float x, y, w, h;
        Color accent;
        boolean danger;

        Btn(String id, String text, float x, float y, float w, float h) {
            this.id = id;
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private ArrayList<Stroke> strokes = new ArrayList<Stroke>();
    private ArrayList<Symbol> symbols = new ArrayList<Symbol>();
    private ArrayList<MapText> texts = new ArrayList<MapText>();
    private ArrayList<Dot> dots = new ArrayList<Dot>();
    private ArrayList<NameTag> constellations = new ArrayList<NameTag>();
    private ArrayList<ArrayList<float[]>> streams = new ArrayList<ArrayList<float[]>>(); // x, y, halfWidth, alpha
    private ArrayList<float[]> abyssBlobs = new ArrayList<float[]>();                   // x, y, radius, depth, phase
    private NameTag abyssTag = null;
    private ArrayList<String> notes = new ArrayList<String>();

    // Undo history and the map view outlive this panel (the Intel screen rebuilds it whenever you come
    // back) and are reset when a different campaign is loaded.
    private static ArrayList<String> undoStack = new ArrayList<String>();
    private static Object sessionOwner = null;
    private static float[] savedView = null; // camX, camY, pixels per map unit

    // Map text (LazyLib). Shared by every panel so text textures are made once per game session.
    private static MapLabels labels = null;
    private static boolean labelsFailed = false;
    private static HashMap<String, Integer> labelIds = new HashMap<String, Integer>();

    // Note editor (LazyLib fonts). If unavailable, notes fall back to a plain text label.
    private static NoteView noteView = null;
    private static boolean noteViewFailed = false;
    private NoteEditor editor;
    private int editorFor = -1;        // viewMode the editor holds
    private int editorSavedVersion = -1;
    private boolean selectingText = false;

    // own key repeat (the game does not repeat held keys for us)
    private int repeatKey = 0;
    private float repeatT = 0f;

    // ---- ui ----
    private CustomPanelAPI panel;
    private PositionAPI pos;
    private float panelW;
    private float panelH;
    private Runnable onClose = null;

    private TooltipMakerAPI labelsTt;
    private ArrayList<Btn> buttons = new ArrayList<Btn>();
    private ArrayList<Btn> flyBtns = new ArrayList<Btn>();
    private float[] flyRect = null; // local x, y, w, h of the open flyout
    private int flyout = FLY_NONE;

    private LabelAPI status;
    private LabelAPI noteLabel;
    private String statusText = "";
    private String wantedStatus = "";
    private float messageTimer = 0f;

    private ArrayList<String> pendingActions = new ArrayList<String>();
    private boolean rebuildNeeded = false;

    private int pendingDelete = -1;

    // ---- note editing ----
    private float nameCheckTimer = 0f;
    private float caretTimer = 0f;
    private boolean caretOn = true;
    private String shownNoteText = null;

    // ---- selection ----
    private int viewMode = 0; // 0 = map, k>0 = notes.get(k-1)
    private int tool = TOOL_PEN;
    private int colorIdx = 0;
    private int sizeIdx = 1;
    private int symIdx = 0;
    private int textSizeIdx = 1;
    private int styleIdx = 0;
    private MapText editingText = null;
    private MapText dragText = null;
    private float dragTextDX, dragTextDY;

    // ---- view ----
    private float minX, maxX, minY, maxY;          // pannable world area
    private float secMinX, secMaxX, secMinY, secMaxY; // grid origin (sector rectangle)
    private boolean viewReady = false;
    private float baseScale = 1f;   // pixels per map unit at zoom 1 (whole sector fits)
    private float zoom = 1f;
    private float targetZoom = 1f;
    private float anchorWX, anchorWY, anchorSX, anchorSY; // point kept under the cursor while zooming
    private float camX = 0f;
    private float camY = 0f;
    private float time = 0f;

    // CRT glitch while the fleet is in the Abyss
    private boolean playerInAbyss = false;
    private float abyssCheckTimer = 0f;
    private float glitchCooldown = 2f;
    private float glitchTime = 0f;
    private float glitchReseed = 0f;
    private float[][] glitchSlices = new float[0][]; // y, height, dx (screen units, from the map bottom)
    private float glitchJolt = 0f;
    private Random noise = new Random();

    // ---- interaction state ----
    private float mx = -10000f, my = -10000f;
    private float mapLastX, mapLastY;
    private boolean mouseIn = false;
    private boolean drawing = false;
    private boolean erasing = false;
    private boolean panning = false;
    private boolean panByLmb = false;
    private Symbol dragSym = null;
    private Stroke curStroke = null;

    public StarNotesPanel() {
    }

    // =====================================================================
    // Construction
    // =====================================================================

    /** Intel screen: creates a child panel inside the intel entry's large-description area. */
    public void build(CustomPanelAPI parent, float width, float height) {
        panel = parent.createCustomPanel(width, height, this);
        setup(width, height);
        parent.addComponent(panel).inTL(0f, 0f);
    }

    /** Full-screen window: uses a panel the dialog already created with this class as its plugin. */
    public void attach(CustomPanelAPI ownPanel, float width, float height, Runnable closeAction) {
        panel = ownPanel;
        onClose = closeAction;
        setup(width, height);
    }

    private void setup(float width, float height) {
        panelW = width;
        panelH = height;

        Object sector = Global.getSector();
        if (sessionOwner != sector) {
            undoStack = new ArrayList<String>();
            savedView = null;
            sessionOwner = sector;
        }
        if (labels == null && !labelsFailed) {
            try {
                labels = new MapLabels();
            } catch (Throwable t) {
                labelsFailed = true;
                Global.getLogger(StarNotesPanel.class).warn("StarNotes: map text unavailable (is LazyLib enabled?)", t);
            }
        }

        if (noteView == null && !noteViewFailed) {
            try {
                noteView = new NoteView();
            } catch (Throwable t) {
                noteViewFailed = true;
                Global.getLogger(StarNotesPanel.class).warn("StarNotes: rich note editor unavailable (is LazyLib enabled?)", t);
            }
        }
        if (noteView != null) {
            editor = new NoteEditor(noteView);
        } else {
            editor = new NoteEditor(new NoteEditor.Metrics() {
                public float width(String str, boolean title) {
                    return str.length() * (title ? 12f : 7f);
                }

                public float lineHeight(boolean title) {
                    return title ? 26f : 18f;
                }
            });
        }

        loadSystems();
        loadSlipstreams();
        loadAbyss();
        load();
        loadNotes();

        viewMode = 0; // always open on the map
        rebuildUI();
    }

    private void close() {
        finishTextEdit();
        syncEditor();
        saveNotesNow();
        endAllDrags();
        if (onClose != null) onClose.run();
    }

    private int label(String text, int kind, Color c) {
        if (labels == null || text == null || text.length() == 0) return -1;
        String key = kind + "|" + c.getRGB() + "|" + text;
        Integer id = labelIds.get(key);
        if (id != null) return id.intValue();
        try {
            int nid = labels.add(text, kind, c);
            labelIds.put(key, Integer.valueOf(nid));
            return nid;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void drawLabel(int id, float x, float y, float alpha) {
        if (id < 0 || labels == null) return;
        try {
            labels.draw(id, x, y, alpha);
        } catch (Throwable t) {
            // a broken label should never take the map down
        }
    }

    /** Recreates the button list and every text label for the current state. */
    private void rebuildUI() {
        if (labelsTt != null) panel.removeComponent(labelsTt);
        labelsTt = null;
        buttons.clear();
        status = null;
        noteLabel = null;
        shownNoteText = null;

        boolean mapView = viewMode == 0 && pendingDelete < 0;

        // ---- left strip ----
        buttons.add(new Btn("plus", "+  New note", 0f, 0f, SIDE_W, TAB_H));
        buttons.add(new Btn("tab0", "Map", 0f, ROW, SIDE_W, TAB_H));
        for (int k = 0; k < notes.size(); k++) {
            buttons.add(new Btn("tab" + (k + 1), nameOf(notes.get(k)), 0f, ROW * (k + 2), SIDE_W - X_ZONE, TAB_H));
        }
        if (onClose != null) {
            buttons.add(new Btn("close", "Close  (Esc)", 0f, panelH - TAB_H, SIDE_W, TAB_H));
        }

        // ---- tool column ----
        if (viewMode == 0) {
            float ix = CONTENT_X + (TOOL_W - ICON) * 0.5f;
            float y = 0f;
            int[] icons = {IC_PEN, IC_ERASER, IC_SYMBOLS, IC_MOVE, IC_TEXTTOOL};
            for (int i = 0; i < TOOL_ORDER.length; i++) {
                int k = TOOL_ORDER[i];
                buttons.add(iconBtn("tool" + k, icons[k], TOOL_TIPS[k], ix, y, ICON));
                y += ICON + ICON_GAP;
            }
            y += 14f;
            buttons.add(iconBtn("undo", IC_UNDO, "Undo the last change (Ctrl+Z)", ix, y, ICON));
            y += ICON + ICON_GAP;
            Btn clr = iconBtn("clearMenu", IC_CLEAR, "Clear the whole drawing (asks to confirm)", ix, y, ICON);
            clr.danger = true;
            buttons.add(clr);
        }

        // ---- note toolbar ----
        if (viewMode > 0 && pendingDelete < 0) {
            buttons.add(iconBtn("checkbox", IC_CHECKBOX,
                    "Add a checkbox to the selected lines (or remove it)", CONTENT_X + 8f, 3f, 28f));
        }

        // ---- delete confirmation ----
        if (pendingDelete >= 0) {
            float dw = 320f;
            float dh = 90f;
            float dx = dlgX(dw);
            float dy = dlgY(dh);
            Btn yes = new Btn("confirmYes", "Delete", dx + 14f, dy + dh - 34f, 130f, 24f);
            yes.danger = true;
            buttons.add(yes);
            buttons.add(new Btn("confirmNo", "Cancel", dx + dw - 144f, dy + dh - 34f, 130f, 24f));
        }

        rebuildFlyout();

        // ---- labels ----
        labelsTt = panel.createUIElement(panelW, panelH, false);
        Color text = Misc.getTextColor();
        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            if (b.icon != IC_TEXT) continue;
            Color c = b.accent != null ? b.accent : (b.danger ? new Color(255, 140, 140) : text);
            LabelAPI l = labelsTt.addPara(b.text, c, 0f);
            l.setAlignment(Alignment.MID);
            l.getPosition().setSize(b.w, b.h - LABEL_DY);
            l.getPosition().inTL(b.x, b.y + LABEL_DY);
        }

        if (mapView) {
            statusText = defaultHint();
            status = labelsTt.addPara(statusText, AMBER, 0f);
            status.getPosition().setSize(Math.max(100f, panelW - MAP_X - 16f), 18f);
            status.getPosition().inTL(MAP_X + 8f, 5f);
        } else if (viewMode > 0 && viewMode - 1 < notes.size() && pendingDelete < 0) {
            float cw = panelW - CONTENT_X;
            if (editorFor != viewMode) {
                editor.setText(notes.get(viewMode - 1));
                editorFor = viewMode;
                editorSavedVersion = editor.getVersion();
                caretOn = true;
                caretTimer = 0f;
            }
            LabelAPI hint = labelsTt.addPara(
                    "Checkbox on the selected lines (click a box to tick it).   First line = title.   Ctrl+A / C / X / V",
                    Misc.getGrayColor(), 0f);
            hint.getPosition().setSize(cw - 50f, 20f);
            hint.getPosition().inTL(CONTENT_X + 44f, 6f);

            if (noteView == null) {
                noteLabel = labelsTt.addPara(" ", text, 0f);
                noteLabel.setAlignment(Alignment.TL);
                noteLabel.getPosition().setSize(cw - 24f, panelH - 44f);
                noteLabel.getPosition().inTL(CONTENT_X + 12f, 34f);
                refreshNoteLabel();
            }
        }

        if (pendingDelete >= 0 && pendingDelete < notes.size()) {
            float dw = 320f;
            float dh = 90f;
            LabelAPI q = labelsTt.addPara("Delete the note \"" + nameOf(notes.get(pendingDelete)) + "\"?\nThis cannot be undone.",
                    Misc.getHighlightColor(), 0f);
            q.setAlignment(Alignment.TMID);
            q.getPosition().setSize(dw - 20f, 40f);
            q.getPosition().inTL(dlgX(dw) + 10f, dlgY(dh) + 10f);
        }

        panel.addUIElement(labelsTt).inTL(0f, 0f);
    }

    private Btn iconBtn(String id, int icon, String tip, float x, float y, float size) {
        Btn b = new Btn(id, null, x, y, size, size);
        b.icon = icon;
        b.tip = tip;
        return b;
    }

    /** Builds the cells of the open pop-out next to its tool icon. No labels, so no full rebuild needed. */
    private void rebuildFlyout() {
        flyBtns.clear();
        flyRect = null;
        if (viewMode != 0 || pendingDelete >= 0 || flyout == FLY_NONE) return;

        Btn anchor = null;
        String anchorId = flyout == FLY_PEN ? "tool0" : (flyout == FLY_SYMBOLS ? "tool2" : (flyout == FLY_TEXT ? "tool4" : "clearMenu"));
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).id.equals(anchorId)) anchor = buttons.get(i);
        }
        if (anchor == null) return;

        float x0 = MAP_X + 4f + FLY_PAD;
        float y0 = anchor.y + FLY_PAD;
        float step = CELL + 4f;
        int cols = 0;
        int rows = 0;

        if (flyout == FLY_PEN) {
            for (int i = 0; i < COLORS.length; i++) {
                Btn b = iconBtn("color" + i, IC_SWATCH, COLOR_NAMES[i], x0 + i * step, y0, CELL);
                b.arg = i;
                b.accent = COLORS[i];
                flyBtns.add(b);
            }
            for (int i = 0; i < SIZES.length; i++) {
                Btn b = iconBtn("size" + i, IC_SIZE, SIZE_NAMES[i] + " line", x0 + i * step, y0 + step, CELL);
                b.arg = i;
                flyBtns.add(b);
            }
            for (int i = 0; i < STYLE_NAMES.length; i++) {
                Btn b = iconBtn("style" + i, IC_STYLE, STYLE_NAMES[i], x0 + i * (step + CELL * 0.5f), y0 + 2f * step, CELL * 1.5f);
                b.h = CELL;
                b.arg = i;
                flyBtns.add(b);
            }
            cols = COLORS.length;
            rows = 3;
        } else if (flyout == FLY_SYMBOLS) {
            for (int i = 0; i < SYMBOL_NAMES.length; i++) {
                Btn b = iconBtn("sym" + i, IC_SYMBOL, SYMBOL_NAMES[i], x0 + i * step, y0, CELL);
                b.arg = i;
                flyBtns.add(b);
            }
            for (int i = 0; i < COLORS.length; i++) {
                Btn b = iconBtn("color" + i, IC_SWATCH, COLOR_NAMES[i], x0 + i * step, y0 + step, CELL);
                b.arg = i;
                b.accent = COLORS[i];
                flyBtns.add(b);
            }
            cols = SYMBOL_NAMES.length;
            rows = 2;
        } else if (flyout == FLY_TEXT) {
            for (int i = 0; i < TSIZE_NAMES.length; i++) {
                Btn b = iconBtn("tsize" + i, IC_TSIZE, TSIZE_NAMES[i], x0 + i * step, y0, CELL);
                b.arg = i;
                flyBtns.add(b);
            }
            for (int i = 0; i < COLORS.length; i++) {
                Btn b = iconBtn("color" + i, IC_SWATCH, COLOR_NAMES[i], x0 + i * step, y0 + step, CELL);
                b.arg = i;
                b.accent = COLORS[i];
                flyBtns.add(b);
            }
            cols = COLORS.length;
            rows = 2;
        } else if (flyout == FLY_CLEAR) {
            Btn b = iconBtn("clearYes", IC_CONFIRM, "Click to erase the whole drawing (Undo can bring it back)", x0, y0, CELL);
            b.danger = true;
            flyBtns.add(b);
            Btn n = iconBtn("flyClose", IC_TEXT, "Keep the drawing", x0 + step, y0, CELL);
            n.icon = -2; // "x" cancel glyph
            flyBtns.add(n);
            cols = 2;
            rows = 1;
        }
        float w = cols * step - 4f + 2f * FLY_PAD;
        float h = rows * step - 4f + 2f * FLY_PAD;
        flyRect = new float[]{MAP_X + 4f, anchor.y, w, h};
    }

    private float dlgX(float dw) {
        return CONTENT_X + (panelW - CONTENT_X - dw) * 0.5f;
    }

    private float dlgY(float dh) {
        return panelH * 0.5f - dh * 0.5f;
    }

    private String defaultHint() {
        return "Wheel: zoom   Right-drag: pan   Ctrl+Z: undo   Hover a star for its name";
    }

    private static String nameOf(String text) {
        if (text == null) return "Untitled";
        String t = text;
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl);
        t = t.trim();
        if (t.length() == 0) return "Untitled";
        if (t.length() > 17) t = t.substring(0, 16) + "...";
        return t;
    }

    private int maxNotes() {
        float reserved = ROW * (onClose != null ? 3f : 2f);
        return Math.max(1, (int) ((panelH - reserved) / ROW));
    }

    // =====================================================================
    // Buttons: hit testing and actions
    // =====================================================================

    private float sx(float localX) {
        return pos.getX() + localX;
    }

    private float sy(float localY) {
        return pos.getY() + pos.getHeight() - localY;
    }

    private boolean over(Btn b, float px, float py) {
        if (pos == null) return false;
        float left = sx(b.x);
        float top = sy(b.y);
        return px >= left && px <= left + b.w && py <= top && py >= top - b.h;
    }

    private boolean inFlyRect(float px, float py) {
        if (flyRect == null || pos == null) return false;
        float left = sx(flyRect[0]);
        float top = sy(flyRect[1]);
        return px >= left && px <= left + flyRect[2] && py <= top && py >= top - flyRect[3];
    }

    private Btn buttonAt(float px, float py) {
        if (pendingDelete < 0) {
            for (int i = flyBtns.size() - 1; i >= 0; i--) {
                if (over(flyBtns.get(i), px, py)) return flyBtns.get(i);
            }
        }
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Btn b = buttons.get(i);
            if (pendingDelete >= 0 && !b.id.startsWith("confirm")) continue;
            if (over(b, px, py)) return b;
        }
        return null;
    }

    private boolean isSelected(Btn b) {
        String id = b.id;
        if (id.startsWith("tab")) return suffix(id, "tab") == viewMode;
        if (id.startsWith("tool")) return suffix(id, "tool") == tool;
        if (id.startsWith("color")) return b.arg == colorIdx;
        if (id.startsWith("size")) return b.arg == sizeIdx;
        if (id.startsWith("sym")) return b.arg == symIdx;
        if (id.startsWith("tsize")) return b.arg == textSizeIdx;
        if (id.startsWith("style")) return b.arg == styleIdx;
        if (id.equals("clearMenu")) return flyout == FLY_CLEAR;
        return false;
    }

    private int suffix(String id, String prefix) {
        try {
            return Integer.parseInt(id.substring(prefix.length()));
        } catch (Throwable t) {
            return -1;
        }
    }

    private void setFlyout(int f) {
        if (flyout == f) return;
        flyout = f;
        rebuildFlyout();
    }

    private void handleAction(String id) {
        if (id.equals("confirmYes")) {
            doDelete();
            return;
        }
        if (id.equals("confirmNo")) {
            pendingDelete = -1;
            rebuildNeeded = true;
            return;
        }
        if (pendingDelete >= 0) return;

        if (id.equals("close")) {
            close();
            return;
        }
        if (id.equals("plus")) {
            addNote();
            return;
        }
        if (id.startsWith("del")) {
            int k = suffix(id, "del");
            if (k >= 0 && k < notes.size()) {
                saveNotesNow();
                endAllDrags();
                flyout = FLY_NONE;
                pendingDelete = k;
                rebuildNeeded = true;
            }
            return;
        }
        if (id.startsWith("tab")) {
            int t = suffix(id, "tab");
            if (t >= 0 && t <= notes.size() && t != viewMode) {
                finishTextEdit();
                syncEditor();
                saveNotesNow();
                endAllDrags();
                flyout = FLY_NONE;
                viewMode = t;
                rebuildNeeded = true;
            }
            return;
        }
        if (id.equals("checkbox")) {
            if (editor != null && editorFor == viewMode && viewMode > 0) {
                if (!editor.toggleCheckboxes()) flash("Checkboxes go on note lines below the title");
                caretOn = true;
                caretTimer = 0f;
            }
            return;
        }
        if (id.equals("undo")) {
            setFlyout(FLY_NONE);
            undo();
            return;
        }
        if (id.equals("clearMenu")) {
            setFlyout(flyout == FLY_CLEAR ? FLY_NONE : FLY_CLEAR);
            return;
        }
        if (id.equals("clearYes")) {
            finishTextEdit();
            pushUndo();
            strokes.clear();
            symbols.clear();
            texts.clear();
            save();
            setFlyout(FLY_NONE);
            flash("Drawing cleared (Undo brings it back)");
            return;
        }
        if (id.equals("flyClose")) {
            setFlyout(FLY_NONE);
            return;
        }
        int k;
        if (id.startsWith("tool") && (k = suffix(id, "tool")) >= 0 && k < 5) {
            int f = k == TOOL_PEN ? FLY_PEN : (k == TOOL_SYMBOL ? FLY_SYMBOLS : (k == TOOL_TEXT ? FLY_TEXT : FLY_NONE));
            if (tool == k) {
                // clicking the active tool toggles its options
                setFlyout(flyout == f ? FLY_NONE : f);
            } else {
                if (k != TOOL_TEXT) finishTextEdit();
                tool = k;
                setFlyout(f);
            }
            return;
        }
        if (id.startsWith("tsize") && (k = suffix(id, "tsize")) >= 0 && k < TSIZE_NAMES.length) {
            textSizeIdx = k;
            if (editingText != null) {
                editingText.size = k;
                save();
            }
            return;
        }
        if (id.startsWith("style") && (k = suffix(id, "style")) >= 0 && k < STYLE_NAMES.length) {
            styleIdx = k;
            return;
        }
        if (id.startsWith("color") && (k = suffix(id, "color")) >= 0 && k < COLORS.length) {
            colorIdx = k;
            if (editingText != null) {
                editingText.color = k;
                save();
            }
            return;
        }
        if (id.startsWith("size") && (k = suffix(id, "size")) >= 0 && k < SIZES.length) {
            sizeIdx = k;
            return;
        }
        if (id.startsWith("sym") && (k = suffix(id, "sym")) >= 0 && k < SYMBOL_NAMES.length) {
            symIdx = k;
            tool = TOOL_SYMBOL;
        }
    }

    // =====================================================================
    // Notes
    // =====================================================================

    private void addNote() {
        if (notes.size() >= maxNotes()) {
            flash("No room for more note tabs");
            return;
        }
        finishTextEdit();
        syncEditor();
        saveNotesNow();
        endAllDrags();
        flyout = FLY_NONE;
        notes.add(""); // the first thing typed becomes the title
        saveNotesNow();
        viewMode = notes.size();
        editorFor = -1;
        rebuildNeeded = true;
    }

    private void doDelete() {
        int k = pendingDelete;
        pendingDelete = -1;
        if (k >= 0 && k < notes.size()) {
            syncEditor();
            notes.remove(k);
            editorFor = -1;
            saveNotesNow();
            if (viewMode == k + 1) {
                viewMode = 0;
            } else if (viewMode > k + 1) {
                viewMode--;
            }
        }
        rebuildNeeded = true;
    }

    private String currentNote() {
        if (viewMode <= 0 || viewMode - 1 >= notes.size()) return null;
        return notes.get(viewMode - 1);
    }

    private void setCurrentNote(String t) {
        if (viewMode <= 0 || viewMode - 1 >= notes.size()) return;
        if (t.length() > MAX_NOTE_CHARS) t = t.substring(0, MAX_NOTE_CHARS);
        notes.set(viewMode - 1, t);
        saveNotesNow(); // immediate: the screen can be closed at any moment
        nameCheckTimer = 0.3f;
        caretOn = true;
        caretTimer = 0f;
        refreshNoteLabel();
    }

    /** Copies the editor's text into the open note if it changed. */
    private void syncEditor() {
        if (editor == null || viewMode <= 0 || editorFor != viewMode || viewMode - 1 >= notes.size()) return;
        if (editor.getVersion() == editorSavedVersion) return;
        editorSavedVersion = editor.getVersion();
        notes.set(viewMode - 1, editor.getText());
        saveNotesNow();
        nameCheckTimer = 0.3f;
        refreshNoteLabel();
    }

    private static boolean isRepeatable(int key) {
        return key == Keyboard.KEY_BACK || key == Keyboard.KEY_DELETE || key == Keyboard.KEY_LEFT
                || key == Keyboard.KEY_RIGHT || key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN;
    }

    private static boolean shiftHeld() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    private static boolean ctrlHeld() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    /** Applies an editing key to the note editor. Used for real key presses and for held-key repeats. */
    private void noteKeyAction(int key, boolean shift, boolean ctrl) {
        if (key == Keyboard.KEY_BACK) editor.backspace(ctrl);
        else if (key == Keyboard.KEY_DELETE) editor.deleteForward(ctrl);
        else if (key == Keyboard.KEY_LEFT) editor.move(NoteEditor.LEFT, shift, ctrl);
        else if (key == Keyboard.KEY_RIGHT) editor.move(NoteEditor.RIGHT, shift, ctrl);
        else if (key == Keyboard.KEY_UP) editor.move(NoteEditor.UP, shift, ctrl);
        else if (key == Keyboard.KEY_DOWN) editor.move(NoteEditor.DOWN, shift, ctrl);
        caretOn = true;
        caretTimer = 0f;
    }

    /** Keyboard input for the open note (rich editor). Returns true if the event was used. */
    private boolean noteKey(InputEventAPI e) {
        if (currentNote() == null) return false;
        int key = e.getEventValue();
        if (key == Keyboard.KEY_ESCAPE) return false;
        if (!e.isKeyDownEvent()) return true; // swallow key-ups so game hotkeys don't fire
        boolean shift = e.isShiftDown();
        boolean ctrl = e.isCtrlDown();

        if (isRepeatable(key)) {
            if (e.isRepeat()) return true; // our own repeat handles held keys
            noteKeyAction(key, shift, ctrl);
            repeatKey = key;
            repeatT = -0.42f;
            return true;
        }
        caretOn = true;
        caretTimer = 0f;
        if (key == Keyboard.KEY_HOME) {
            editor.move(NoteEditor.HOME, shift, ctrl);
            return true;
        }
        if (key == Keyboard.KEY_END) {
            editor.move(NoteEditor.END, shift, ctrl);
            return true;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            editor.insert("\n");
            return true;
        }
        if (ctrl) {
            if (key == Keyboard.KEY_A) {
                editor.selectAll();
            } else if (key == Keyboard.KEY_C) {
                if (editor.hasSelection()) writeClipboard(editor.selectedText());
            } else if (key == Keyboard.KEY_X) {
                String cut = editor.cut();
                if (cut != null) writeClipboard(cut);
            } else if (key == Keyboard.KEY_V) {
                String clip = readClipboard();
                if (clip != null && clip.length() > 0) editor.insert(clip);
            }
            return true;
        }
        char c = e.getEventChar();
        if (c == '\t') c = ' ';
        if (c >= 32 && c != 127) editor.insert(String.valueOf(c));
        return true;
    }

    private static void writeClipboard(String s) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
        } catch (Throwable t) {
            // clipboard unavailable
        }
    }

    private float edLeftLocal() {
        return CONTENT_X + 16f;
    }

    private float edTopLocal() {
        return 34f;
    }

    private float edW() {
        return panelW - CONTENT_X - 32f;
    }

    private float edH() {
        return panelH - 44f;
    }

    private boolean inEditor(float px, float py) {
        if (pos == null) return false;
        float l = sx(edLeftLocal()) - 8f, t = sy(edTopLocal()) + 6f;
        return px >= l && px <= l + edW() + 16f && py <= t && py >= t - edH() - 10f;
    }

    private void refreshNoteLabel() {
        if (noteLabel == null) return;
        String t = editor != null && editorFor == viewMode ? editor.getText() : currentNote();
        if (t == null) t = "";
        String shown = t + (caretOn ? "_" : " ");
        if (!shown.equals(shownNoteText)) {
            shownNoteText = shown;
            noteLabel.setText(shown);
        }
    }

    /** Keyboard input for the open note. Returns true if the event was used. */
    private boolean editNote(InputEventAPI e) {
        String t = currentNote();
        if (t == null) return false;
        int key = e.getEventValue();
        if (key == Keyboard.KEY_ESCAPE) return false;

        if (!e.isKeyDownEvent()) return true; // swallow key-ups so game hotkeys don't fire

        if (key == Keyboard.KEY_BACK) {
            if (t.length() == 0) return true;
            if (e.isCtrlDown()) {
                int end = t.length();
                while (end > 0 && Character.isWhitespace(t.charAt(end - 1)) && t.charAt(end - 1) != '\n') end--;
                while (end > 0 && !Character.isWhitespace(t.charAt(end - 1))) end--;
                if (end == t.length()) end = t.length() - 1;
                setCurrentNote(t.substring(0, end));
            } else {
                setCurrentNote(t.substring(0, t.length() - 1));
            }
            return true;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            setCurrentNote(t + "\n");
            return true;
        }
        if (e.isCtrlDown() && key == Keyboard.KEY_V) {
            String clip = readClipboard();
            if (clip != null && clip.length() > 0) setCurrentNote(t + clip);
            return true;
        }
        if (e.isCtrlDown()) return true;

        char c = e.getEventChar();
        if (c == '\t') c = ' ';
        if (c >= 32 && c != 127) {
            setCurrentNote(t + c);
        }
        return true;
    }

    private static String readClipboard() {
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (o instanceof String) {
                return ((String) o).replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ');
            }
        } catch (Throwable t) {
            // clipboard unavailable
        }
        return null;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') continue;
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                if (d == 'n') {
                    sb.append('\n');
                    i++;
                } else if (d == '\\') {
                    sb.append('\\');
                    i++;
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void saveNotesNow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            sb.append(esc(notes.get(i))).append('\n');
        }
        Global.getSector().getPersistentData().put(NOTES_KEY, sb.toString());
    }

    private void loadNotes() {
        notes.clear();
        Object o = Global.getSector().getPersistentData().get(NOTES_KEY);
        if (!(o instanceof String)) return;
        String data = (String) o;
        if (data.length() == 0) return;
        String[] lines = data.split("\n", -1);
        for (int i = 0; i < lines.length - 1; i++) {
            notes.add(unesc(lines[i]));
        }
    }

    // =====================================================================
    // Map data: systems, constellations, slipstreams, the Abyss
    // =====================================================================

    private void loadSystems() {
        dots.clear();
        constellations.clear();
        List<StarSystemAPI> abyssal = null;
        try {
            abyssal = Misc.getAbyssalSystems();
        } catch (Throwable t) {
            // older data: no abyssal list
        }
        IdentityHashMap<Constellation, Boolean> seen = new IdentityHashMap<Constellation, Boolean>();

        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        List systems = Global.getSector().getStarSystems();
        for (int i = 0; i < systems.size(); i++) {
            StarSystemAPI s = (StarSystemAPI) systems.get(i);
            if (s.hasTag("theme_hidden")) continue;
            boolean entered = s.isEnteredByPlayer();
            if (!entered && abyssal != null && abyssal.contains(s)) continue; // the game hides these too
            Vector2f loc = s.getLocation();
            Dot d = new Dot();
            d.x = loc.x;
            d.y = loc.y;
            d.entered = entered;
            d.name = s.getBaseName();
            d.labelId = label(d.name, MapLabels.MED, entered ? AMBER : AMBER_DIM);
            dots.add(d);
            x0 = Math.min(x0, d.x);
            x1 = Math.max(x1, d.x);
            y0 = Math.min(y0, d.y);
            y1 = Math.max(y1, d.y);

            try {
                Constellation c = s.getConstellation();
                if (c != null && !seen.containsKey(c) && c.getName() != null) {
                    seen.put(c, Boolean.TRUE);
                    NameTag n = new NameTag();
                    Vector2f cl = c.getLocation();
                    n.x = cl.x;
                    n.y = cl.y;
                    n.labelId = label(spaced(c.getName().toUpperCase()), MapLabels.BIG, AMBER);
                    constellations.add(n);
                }
            } catch (Throwable t) {
                // no constellation
            }
        }
        if (dots.isEmpty()) {
            x0 = -10000f;
            x1 = 10000f;
            y0 = -10000f;
            y1 = 10000f;
        }

        // the sector rectangle (grid origin); falls back to the systems' bounds
        float sw = 0f, sh = 0f;
        try {
            sw = Global.getSettings().getFloat("sectorWidth");
            sh = Global.getSettings().getFloat("sectorHeight");
        } catch (Throwable t) {
            // not set
        }
        if (sw > 1000f && sh > 1000f) {
            secMinX = -sw * 0.5f;
            secMaxX = sw * 0.5f;
            secMinY = -sh * 0.5f;
            secMaxY = sh * 0.5f;
        } else {
            secMinX = x0 - 4000f;
            secMaxX = x1 + 4000f;
            secMinY = y0 - 4000f;
            secMaxY = y1 + 4000f;
        }
        minX = Math.min(secMinX, x0 - 4000f);
        maxX = Math.max(secMaxX, x1 + 4000f);
        minY = Math.min(secMinY, y0 - 4000f);
        maxY = Math.max(secMaxY, y1 + 4000f);
    }

    /** "ORION" -> "O R I O N" for the wide-tracked look of the game's constellation labels. */
    private static String spaced(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append(s.charAt(i) == ' ' ? "  " : " ");
            if (s.charAt(i) != ' ') sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** Reads the live hyperspace slipstreams, keeping the parts the game's own map would show. */
    private void loadSlipstreams() {
        streams.clear();
        try {
            boolean sensorsOn = Global.getSettings().isCampaignSensorsOn();
            List<CampaignTerrainAPI> terrain = Global.getSector().getHyperspace().getTerrainCopy();
            for (int i = 0; i < terrain.size(); i++) {
                CampaignTerrainPlugin p = terrain.get(i).getPlugin();
                if (!(p instanceof SlipstreamTerrainPlugin2)) continue;
                SlipstreamTerrainPlugin2 sp = (SlipstreamTerrainPlugin2) p;
                float streamAlpha = sp.isDespawning() ? 0.45f : 1f;
                List<SlipstreamTerrainPlugin2.SlipstreamSegment> segs = sp.getSegments();
                ArrayList<float[]> run = null;
                for (int j = 0; j < segs.size(); j++) {
                    SlipstreamTerrainPlugin2.SlipstreamSegment seg = segs.get(j);
                    float b = 1f;
                    try {
                        if (seg.fader != null) b = seg.fader.getBrightness();
                    } catch (Throwable t) {
                        b = 1f;
                    }
                    boolean visible = (seg.discovered || !sensorsOn) && seg.loc != null && b > 0.05f;
                    if (visible) {
                        if (run == null) run = new ArrayList<float[]>();
                        run.add(new float[]{seg.loc.x, seg.loc.y, Math.max(50f, seg.width * 0.5f), b * streamAlpha});
                    } else {
                        if (run != null && run.size() >= 2) streams.add(run);
                        run = null;
                    }
                }
                if (run != null && run.size() >= 2) streams.add(run);
            }
        } catch (Throwable t) {
            Global.getLogger(StarNotesPanel.class).warn("StarNotes: could not read slipstreams", t);
        }
    }

    /** Samples the game's own abyssal-depth function to shape a red cloud over the Abyss. */
    private void loadAbyss() {
        abyssBlobs.clear();
        abyssTag = null;
        try {
            float pad = 30000f;
            float ax0 = minX - pad, ax1 = maxX + pad, ay0 = minY - pad, ay1 = maxY + pad;
            float step = 2500f;
            while (((ax1 - ax0) / step) * ((ay1 - ay0) / step) > 9000f) step *= 1.25f;
            Random r = new Random(1337L);
            float sx = 0f, sy = 0f, sw = 0f;
            float bx0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by0 = Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
            Vector2f probe = new Vector2f();
            for (float x = ax0; x <= ax1; x += step) {
                for (float y = ay0; y <= ay1; y += step) {
                    probe.x = x;
                    probe.y = y;
                    float d = Misc.getAbyssalDepth(probe);
                    if (d <= 0.01f) continue;
                    d = Math.min(1f, d);
                    float jx = (r.nextFloat() - 0.5f) * step * 0.8f;
                    float jy = (r.nextFloat() - 0.5f) * step * 0.8f;
                    float rad = step * (1.0f + r.nextFloat() * 0.7f);
                    abyssBlobs.add(new float[]{x + jx, y + jy, rad, d, r.nextFloat() * 6.283f});
                    sx += x * d;
                    sy += y * d;
                    sw += d;
                    bx0 = Math.min(bx0, x);
                    bx1 = Math.max(bx1, x);
                    by0 = Math.min(by0, y);
                    by1 = Math.max(by1, y);
                }
            }
            if (sw > 0f) {
                abyssTag = new NameTag();
                // bottom-left of the region: the deep sample closest to a point a quarter of the way in
                float tx = bx0 + (bx1 - bx0) * 0.22f;
                float ty = by0 + (by1 - by0) * 0.18f;
                float bestD = Float.MAX_VALUE;
                abyssTag.x = sx / sw;
                abyssTag.y = sy / sw;
                for (int i = 0; i < abyssBlobs.size(); i++) {
                    float[] b = abyssBlobs.get(i);
                    if (b[3] < 0.3f) continue;
                    float d = (b[0] - tx) * (b[0] - tx) + (b[1] - ty) * (b[1] - ty);
                    if (d < bestD) {
                        bestD = d;
                        abyssTag.x = b[0];
                        abyssTag.y = b[1];
                    }
                }
                abyssTag.labelId = label("T H E   A B Y S S", MapLabels.HUGE, new Color(235, 60, 50));
                // make sure the cloud is inside the pannable area
                minX = Math.min(minX, bx0);
                maxX = Math.max(maxX, bx1);
                minY = Math.min(minY, by0);
                maxY = Math.max(maxY, by1);
            }
        } catch (Throwable t) {
            Global.getLogger(StarNotesPanel.class).warn("StarNotes: could not map the Abyss", t);
        }
    }

    // =====================================================================
    // View and coordinate helpers (screen space)
    // =====================================================================

    private void ensureView() {
        if (viewReady || pos == null) return;
        float mw = mapW();
        float mh = mapH();
        if (mw <= 1f || mh <= 1f) return;
        // zoom 1 = the whole sector rectangle fits
        baseScale = Math.min(mw / (secMaxX - secMinX), mh / (secMaxY - secMinY)) * 0.96f;
        if (savedView != null) {
            camX = savedView[0];
            camY = savedView[1];
            zoom = clampZoom(savedView[2] / baseScale);
        } else {
            camX = (secMinX + secMaxX) * 0.5f;
            camY = (secMinY + secMaxY) * 0.5f;
            zoom = 1f;
        }
        targetZoom = zoom;
        viewReady = true;
    }

    private float clampZoom(float z) {
        return Math.max(0.55f, Math.min(40f, z));
    }

    private void clampCam() {
        camX = Math.max(minX, Math.min(maxX, camX));
        camY = Math.max(minY, Math.min(maxY, camY));
    }

    private void rememberView() {
        savedView = new float[]{camX, camY, scale()};
    }

    private float scale() {
        return baseScale * zoom;
    }

    private float mapL() {
        return pos.getX() + MAP_X;
    }

    private float mapB() {
        return pos.getY();
    }

    private float mapW() {
        return pos.getWidth() - MAP_X;
    }

    private float mapH() {
        return pos.getHeight();
    }

    private float toSX(float wx) {
        return mapL() + mapW() * 0.5f + (wx - camX) * scale();
    }

    private float toSY(float wy) {
        return mapB() + mapH() * 0.5f + (wy - camY) * scale();
    }

    private float toWX(float px) {
        return camX + (px - (mapL() + mapW() * 0.5f)) / scale();
    }

    private float toWY(float py) {
        return camY + (py - (mapB() + mapH() * 0.5f)) / scale();
    }

    private boolean inMap(float px, float py) {
        return pos != null && viewReady && px >= mapL() && px <= mapL() + mapW() && py >= mapB() && py <= mapB() + mapH();
    }

    private boolean onScreen(float px, float py, float margin) {
        return px >= mapL() - margin && px <= mapL() + mapW() + margin && py >= mapB() - margin && py <= mapB() + mapH() + margin;
    }

    private float tabBottomScreen(int tabIndex) {
        return sy(ROW * (tabIndex + 1)) - TAB_H;
    }

    private int noteTabAt(float px, float py) {
        if (pos == null || px < pos.getX() || px > pos.getX() + SIDE_W) return -1;
        for (int t = 1; t <= notes.size(); t++) {
            float b = tabBottomScreen(t);
            if (py >= b && py <= b + TAB_H) return t;
        }
        return -1;
    }

    private boolean inDeleteZone(float px, float py, int tabIndex) {
        if (tabIndex <= 0) return false;
        float left = pos.getX() + SIDE_W - X_ZONE;
        float b = tabBottomScreen(tabIndex);
        return px >= left && px <= left + X_ZONE && py >= b && py <= b + TAB_H;
    }

    // =====================================================================
    // Drawing persistence (plain text so saves never depend on this mod's classes)
    // =====================================================================

    private String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            sb.append("S;").append(s.color).append(';').append(s.width).append(';');
            for (int j = 0; j < s.pts.size(); j++) {
                float[] p = s.pts.get(j);
                if (j > 0) sb.append(',');
                sb.append(p[0]).append(',').append(p[1]);
            }
            sb.append(';').append(s.style).append('\n');
        }
        for (int i = 0; i < symbols.size(); i++) {
            Symbol m = symbols.get(i);
            sb.append("M;").append(m.type).append(';').append(m.color).append(';').append(m.x).append(';').append(m.y).append('\n');
        }
        for (int i = 0; i < texts.size(); i++) {
            MapText t = texts.get(i);
            if (t.text.trim().length() == 0) continue;
            sb.append("T;").append(t.color).append(';').append(t.size).append(';').append(t.x).append(';').append(t.y)
                    .append(';').append(esc(t.text)).append('\n');
        }
        return sb.toString();
    }

    private void deserialize(String data) {
        strokes.clear();
        symbols.clear();
        texts.clear();
        editingText = null;
        dragText = null;
        if (data == null || data.length() == 0) return;
        String[] lines = data.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            try {
                if (line.startsWith("T;")) {
                    String[] t = line.split(";", 6);
                    if (t.length >= 6) {
                        MapText mt = new MapText();
                        mt.color = Integer.parseInt(t[1]);
                        mt.size = Math.max(0, Math.min(3, Integer.parseInt(t[2])));
                        mt.x = Float.parseFloat(t[3]);
                        mt.y = Float.parseFloat(t[4]);
                        mt.text = unesc(t[5]);
                        texts.add(mt);
                    }
                    continue;
                }
                String[] f = line.split(";");
                if (f[0].equals("S") && f.length >= 4) {
                    Stroke s = new Stroke();
                    s.color = Integer.parseInt(f[1]);
                    s.width = Float.parseFloat(f[2]);
                    if (f.length >= 5) s.style = Math.max(0, Math.min(2, Integer.parseInt(f[4])));
                    String[] n = f[3].split(",");
                    for (int j = 0; j + 1 < n.length; j += 2) {
                        s.pts.add(new float[]{Float.parseFloat(n[j]), Float.parseFloat(n[j + 1])});
                    }
                    if (s.pts.size() > 0) strokes.add(s);
                } else if (f[0].equals("M") && f.length >= 5) {
                    Symbol m = new Symbol();
                    m.type = Integer.parseInt(f[1]);
                    m.color = Integer.parseInt(f[2]);
                    m.x = Float.parseFloat(f[3]);
                    m.y = Float.parseFloat(f[4]);
                    symbols.add(m);
                }
            } catch (Throwable t) {
                // skip a corrupt line
            }
        }
    }

    private void save() {
        Global.getSector().getPersistentData().put(SAVE_KEY, serialize());
    }

    private void load() {
        Object o = Global.getSector().getPersistentData().get(SAVE_KEY);
        if (o instanceof String) {
            deserialize((String) o);
        }
    }

    private void pushUndo() {
        String snap = serialize();
        if (!undoStack.isEmpty() && undoStack.get(undoStack.size() - 1).equals(snap)) return;
        undoStack.add(snap);
        if (undoStack.size() > 50) undoStack.remove(0);
    }

    private void undo() {
        finishTextEdit();
        endAllDrags();
        String now = serialize();
        while (!undoStack.isEmpty() && undoStack.get(undoStack.size() - 1).equals(now)) {
            undoStack.remove(undoStack.size() - 1);
        }
        if (undoStack.isEmpty()) {
            flash("Nothing to undo");
            return;
        }
        deserialize(undoStack.remove(undoStack.size() - 1));
        save();
        flash("Undone");
    }

    private void flash(String msg) {
        wantedStatus = msg;
        messageTimer = 2.5f;
    }

    // =====================================================================
    // Input
    // =====================================================================

    public void positionChanged(PositionAPI position) {
        this.pos = position;
    }

    public void processInput(List<InputEventAPI> events) {
        if (pos == null) return;
        ensureView();

        for (int i = 0; i < events.size(); i++) {
            InputEventAPI e = events.get(i);
            if (e.isConsumed()) continue;

            // ---------- keyboard ----------
            if (e.isKeyboardEvent()) {
                int key = e.getEventValue();
                if (editingText != null && viewMode == 0 && pendingDelete < 0) {
                    mapTextKey(e);
                    e.consume();
                    continue;
                }
                if (key == Keyboard.KEY_ESCAPE && e.isKeyDownEvent()) {
                    if (pendingDelete >= 0) {
                        pendingActions.add("confirmNo");
                        e.consume();
                        continue;
                    }
                    if (flyout != FLY_NONE) {
                        pendingActions.add("flyClose");
                        e.consume();
                        continue;
                    }
                    if (onClose != null) {
                        pendingActions.add("close");
                        e.consume();
                        continue;
                    }
                    continue; // Intel screen: let Escape close it
                }
                if (key == Keyboard.KEY_ESCAPE) {
                    if (onClose != null) e.consume();
                    continue;
                }
                if (pendingDelete >= 0) continue;
                if (viewMode > 0) {
                    if (noteKey(e)) e.consume();
                    continue;
                }
                if (e.isKeyDownEvent() && e.isCtrlDown() && key == Keyboard.KEY_Z) {
                    pendingActions.add("undo");
                    e.consume();
                }
                continue;
            }

            if (!e.isMouseEvent()) continue;

            mx = e.getX();
            my = e.getY();

            // ---------- note editor: click, drag-select, double-click, wheel ----------
            if (viewMode > 0 && pendingDelete < 0 && editor != null && editorFor == viewMode) {
                float lx = mx - sx(edLeftLocal());
                float ly = sy(edTopLocal()) - my;
                if (e.isLMBDownEvent() && inEditor(mx, my) && editor.checkHit(lx, ly)) {
                    e.consume(); // ticked or unticked a checkbox
                    continue;
                }
                if (e.isLMBDownEvent() && inEditor(mx, my)) {
                    if (e.isDoubleClick()) {
                        editor.doubleClick(lx, ly);
                    } else {
                        editor.mouseDown(lx, ly, e.isShiftDown());
                        selectingText = true;
                    }
                    caretOn = true;
                    caretTimer = 0f;
                    e.consume();
                    continue;
                }
                if (e.isMouseMoveEvent() && selectingText) {
                    editor.mouseDrag(lx, ly);
                    continue;
                }
                if (e.isLMBUpEvent() && selectingText) {
                    selectingText = false;
                    e.consume();
                    continue;
                }
                if (e.isMouseScrollEvent() && inEditor(mx, my)) {
                    editor.scrollBy(e.getEventValue() > 0 ? -40f : 40f);
                    e.consume();
                    continue;
                }
            }

            if (e.isLMBDownEvent()) {
                Btn b = buttonAt(mx, my);
                if (b != null) {
                    pendingActions.add(b.id);
                    e.consume();
                    continue;
                }
                if (pendingDelete >= 0) {
                    e.consume();
                    continue;
                }
                if (inFlyRect(mx, my)) {
                    e.consume();
                    continue;
                }
                int t = noteTabAt(mx, my);
                if (t > 0 && inDeleteZone(mx, my, t)) {
                    pendingActions.add("del" + (t - 1));
                    e.consume();
                    continue;
                }
            }

            mapInput(e);
        }
    }

    private void mapInput(InputEventAPI e) {
        if (e.isConsumed() || !e.isMouseEvent()) return;
        if (viewMode != 0 || pendingDelete >= 0 || !viewReady || pos == null) return;

        float ex = e.getX();
        float ey = e.getY();
        mx = ex;
        my = ey;
        float dx = ex - mapLastX;
        float dy = ey - mapLastY;
        mapLastX = ex;
        mapLastY = ey;

        boolean blocked = inFlyRect(ex, ey) || buttonAt(ex, ey) != null;
        boolean in = inMap(ex, ey) && !blocked;
        mouseIn = in;

        if (e.isMouseScrollEvent()) {
            if (in) {
                // smooth zoom: set a target and ease toward it in advance(), keeping the point under
                // the cursor fixed
                float factor = e.getEventValue() > 0 ? 1.3f : 1f / 1.3f;
                anchorSX = ex;
                anchorSY = ey;
                anchorWX = toWX(ex);
                anchorWY = toWY(ey);
                targetZoom = clampZoom(targetZoom * factor);
                e.consume();
            }
            return;
        }

        if (e.isMouseMoveEvent()) {
            onMove(dx, dy);
            return;
        }

        if (e.isLMBDownEvent()) {
            if (in) {
                onLmbDown();
                e.consume();
            }
        } else if (e.isLMBUpEvent()) {
            if (drawing || erasing || dragSym != null || panByLmb) {
                onLmbUp();
                e.consume();
            }
        } else if (e.isRMBDownEvent() || (e.isMouseDownEvent() && e.getEventValue() == 2)) {
            if (in) {
                panning = true;
                e.consume();
            }
        } else if (e.isRMBUpEvent() || (e.isMouseUpEvent() && e.getEventValue() == 2)) {
            if (panning && !panByLmb) {
                panning = false;
                e.consume();
            }
        }
    }

    private void onLmbDown() {
        float wx = toWX(mx);
        float wy = toWY(my);
        if (flyout != FLY_NONE) setFlyout(FLY_NONE); // drawing closes the pop-out

        if (tool == TOOL_TEXT) {
            MapText hit = textAt(mx, my);
            if (hit != null) {
                if (hit != editingText) finishTextEdit();
                pushUndo();
                editingText = hit;
                dragText = hit;
                dragTextDX = hit.x - wx;
                dragTextDY = hit.y - wy;
            } else {
                finishTextEdit();
                pushUndo();
                MapText t = new MapText();
                t.x = wx;
                t.y = wy + 6f / scale(); // put the first line's middle about where you clicked
                t.color = colorIdx;
                t.size = textSizeIdx;
                texts.add(t);
                editingText = t;
            }
            caretOn = true;
            caretTimer = 0f;
            return;
        }
        finishTextEdit();

        if (tool == TOOL_PEN) {
            pushUndo();
            curStroke = new Stroke();
            curStroke.color = colorIdx;
            curStroke.width = SIZES[sizeIdx];
            curStroke.style = styleIdx;
            curStroke.pts.add(new float[]{wx, wy});
            strokes.add(curStroke);
            drawing = true;
        } else if (tool == TOOL_ERASER) {
            pushUndo();
            erasing = true;
            eraseAt();
        } else if (tool == TOOL_SYMBOL) {
            Symbol hit = symbolAt(mx, my);
            pushUndo();
            if (hit != null) {
                dragSym = hit;
            } else {
                Symbol m = new Symbol();
                m.type = symIdx;
                m.color = colorIdx;
                m.x = wx;
                m.y = wy;
                symbols.add(m);
                save();
            }
        } else if (tool == TOOL_MOVE) {
            Symbol hit = symbolAt(mx, my);
            MapText th = hit == null ? textAt(mx, my) : null;
            if (hit != null) {
                pushUndo();
                dragSym = hit;
            } else if (th != null) {
                pushUndo();
                dragText = th;
                dragTextDX = th.x - wx;
                dragTextDY = th.y - wy;
            } else {
                panning = true;
                panByLmb = true;
            }
        }
    }

    private void onLmbUp() {
        if (drawing && curStroke != null && curStroke.pts.size() == 1) {
            float[] p = curStroke.pts.get(0);
            curStroke.pts.add(new float[]{p[0], p[1]});
        }
        boolean changed = drawing || erasing || dragSym != null || dragText != null;
        dragText = null;
        drawing = false;
        erasing = false;
        curStroke = null;
        dragSym = null;
        if (panByLmb) {
            panning = false;
            panByLmb = false;
        }
        if (changed) save();
    }

    private void endAllDrags() {
        if (drawing || erasing || dragSym != null || dragText != null || panByLmb) onLmbUp();
        panning = false;
        panByLmb = false;
    }

    private void onMove(float dx, float dy) {
        if (drawing && curStroke != null) {
            float[] last = curStroke.pts.get(curStroke.pts.size() - 1);
            float lx = toSX(last[0]);
            float ly = toSY(last[1]);
            float ddx = mx - lx;
            float ddy = my - ly;
            if (ddx * ddx + ddy * ddy >= 4f) {
                curStroke.pts.add(new float[]{toWX(mx), toWY(my)});
            }
        } else if (erasing) {
            eraseAt();
        } else if (dragSym != null) {
            dragSym.x = toWX(mx);
            dragSym.y = toWY(my);
        } else if (dragText != null) {
            dragText.x = toWX(mx) + dragTextDX;
            dragText.y = toWY(my) + dragTextDY;
        } else if (panning) {
            camX -= dx / scale();
            camY -= dy / scale();
            anchorWX -= dx / scale(); // keep an in-progress smooth zoom consistent with the pan
            anchorWY -= dy / scale();
            clampCam();
            rememberView();
        }
    }

    private Symbol symbolAt(float px, float py) {
        for (int i = symbols.size() - 1; i >= 0; i--) {
            Symbol m = symbols.get(i);
            float dx = toSX(m.x) - px;
            float dy = toSY(m.y) - py;
            if (dx * dx + dy * dy <= 13f * 13f) return m;
        }
        return null;
    }

    private void eraseAt() {
        float r = 12f;
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Stroke s = strokes.get(i);
            if (strokeHit(s, mx, my, r + s.width * 0.5f)) {
                strokes.remove(i);
            }
        }
        for (int i = symbols.size() - 1; i >= 0; i--) {
            Symbol m = symbols.get(i);
            float dx = toSX(m.x) - mx;
            float dy = toSY(m.y) - my;
            if (dx * dx + dy * dy <= (r + 10f) * (r + 10f)) {
                symbols.remove(i);
            }
        }
        for (int i = texts.size() - 1; i >= 0; i--) {
            float[] b = textBounds(texts.get(i));
            if (mx >= b[0] - r && mx <= b[2] + r && my >= b[1] - r && my <= b[3] + r) {
                if (texts.get(i) == editingText) editingText = null;
                texts.remove(i);
            }
        }
    }

    /** Screen-space box of a map text: left, bottom, right, top. */
    private float[] textBounds(MapText t) {
        float left = toSX(t.x);
        float top = toSY(t.y);
        float w = 30f;
        float h = 16f;
        if (labels != null) {
            int kind = MapLabels.TEXT_S + t.size;
            String[] ls = (t.text.length() == 0 ? " " : t.text).split("\n", -1);
            w = 6f;
            for (int i = 0; i < ls.length; i++) w = Math.max(w, labels.measure(kind, ls[i]));
            h = Math.max(1, ls.length) * labels.lineHeight(kind);
        }
        return new float[]{left - 3f, top - h - 3f, left + Math.max(w, 12f) + 3f, top + 3f};
    }

    private MapText textAt(float px, float py) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            float[] b = textBounds(texts.get(i));
            if (px >= b[0] && px <= b[2] && py >= b[1] && py <= b[3]) return texts.get(i);
        }
        return null;
    }

    /** Stops typing into a map text; an empty one is removed. */
    private void finishTextEdit() {
        if (editingText == null) return;
        if (editingText.text.trim().length() == 0) texts.remove(editingText);
        editingText = null;
        save();
    }

    private void mapTextAction(int key, boolean ctrl) {
        if (editingText == null) return;
        String t = editingText.text;
        if (key == Keyboard.KEY_BACK && t.length() > 0) {
            int k = t.length() - 1;
            if (ctrl) {
                while (k > 0 && t.charAt(k - 1) == ' ') k--;
                while (k > 0 && t.charAt(k - 1) != ' ' && t.charAt(k - 1) != '\n') k--;
            }
            editingText.text = t.substring(0, k);
        }
        caretOn = true;
        caretTimer = 0f;
    }

    /** Typing into the map text being edited. Enter or Esc finishes, Shift+Enter starts a new line. */
    private void mapTextKey(InputEventAPI e) {
        if (!e.isKeyDownEvent()) return;
        int key = e.getEventValue();
        if (key == Keyboard.KEY_ESCAPE || ((key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) && !e.isShiftDown())) {
            finishTextEdit();
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (editingText.text.length() < MAX_MAP_TEXT) editingText.text += "\n";
        } else if (key == Keyboard.KEY_BACK) {
            if (e.isRepeat()) return;
            mapTextAction(key, e.isCtrlDown());
            repeatKey = key;
            repeatT = -0.42f;
        } else if (e.isCtrlDown() && key == Keyboard.KEY_V) {
            String clip = readClipboard();
            if (clip != null) {
                String add = clip.length() > MAX_MAP_TEXT - editingText.text.length()
                        ? clip.substring(0, Math.max(0, MAX_MAP_TEXT - editingText.text.length())) : clip;
                editingText.text += add;
            }
        } else if (!e.isCtrlDown()) {
            char c = e.getEventChar();
            if (c == '\t') c = ' ';
            if (c >= 32 && c != 127 && editingText.text.length() < MAX_MAP_TEXT) editingText.text += c;
        }
        caretOn = true;
        caretTimer = 0f;
        save();
    }

    private boolean strokeHit(Stroke s, float px, float py, float r) {
        int n = s.pts.size();
        if (n == 0) return false;
        float[] p0 = s.pts.get(0);
        float ax = toSX(p0[0]);
        float ay = toSY(p0[1]);
        if (n == 1) {
            return dist2(ax, ay, px, py) <= r * r;
        }
        for (int i = 1; i < n; i++) {
            float[] p = s.pts.get(i);
            float bx = toSX(p[0]);
            float by = toSY(p[1]);
            if (segDist2(px, py, ax, ay, bx, by) <= r * r) return true;
            ax = bx;
            ay = by;
        }
        return false;
    }

    private static float dist2(float ax, float ay, float bx, float by) {
        float dx = ax - bx;
        float dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static float segDist2(float px, float py, float ax, float ay, float bx, float by) {
        float vx = bx - ax;
        float vy = by - ay;
        float len2 = vx * vx + vy * vy;
        if (len2 < 0.0001f) return dist2(px, py, ax, ay);
        float t = ((px - ax) * vx + (py - ay) * vy) / len2;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return dist2(px, py, ax + t * vx, ay + t * vy);
    }

    // =====================================================================
    // Per-frame logic
    // =====================================================================

    public void buttonPressed(Object buttonId) {
        // not used: this panel has no game buttons
    }

    public void advance(float amount) {
        if (pos == null) return;
        ensureView();
        time += amount;
        updateGlitch(amount);

        // end a drag whose mouse button is no longer held (release can happen off-panel)
        try {
            if (!Mouse.isButtonDown(0) && (drawing || erasing || dragSym != null || panByLmb)) onLmbUp();
            if (panning && !panByLmb && !Mouse.isButtonDown(1) && !Mouse.isButtonDown(2)) panning = false;
        } catch (Throwable t) {
            // ignore
        }

        // held-key repeat for the note editor and map text
        if (repeatKey != 0) {
            boolean target = (viewMode > 0 && editorFor == viewMode) || editingText != null;
            if (!target || !Keyboard.isKeyDown(repeatKey)) {
                repeatKey = 0;
            } else {
                repeatT += amount;
                int guard = 0;
                while (repeatT >= 0.035f && guard++ < 6) {
                    repeatT -= 0.035f;
                    if (editingText != null) {
                        mapTextAction(repeatKey, ctrlHeld());
                        save();
                    } else {
                        noteKeyAction(repeatKey, shiftHeld(), ctrlHeld());
                    }
                }
            }
        }
        if (selectingText) {
            try {
                if (!Mouse.isButtonDown(0)) selectingText = false;
            } catch (Throwable t) {
                selectingText = false;
            }
        }
        syncEditor();
        if (editingText != null || (viewMode > 0 && noteView != null)) {
            caretTimer += amount;
            if (caretTimer >= 0.5f) {
                caretTimer = 0f;
                caretOn = !caretOn;
            }
        }

        // smooth zoom toward the wheel target
        if (viewReady && Math.abs(targetZoom - zoom) > zoom * 0.0005f) {
            float k = Math.min(1f, amount * 12f);
            zoom = (float) Math.exp(Math.log(zoom) + (Math.log(targetZoom) - Math.log(zoom)) * k);
            if (Math.abs(targetZoom - zoom) <= zoom * 0.0005f) zoom = targetZoom;
            camX = anchorWX - (anchorSX - (mapL() + mapW() * 0.5f)) / scale();
            camY = anchorWY - (anchorSY - (mapB() + mapH() * 0.5f)) / scale();
            clampCam();
            rememberView();
        }

        if (!pendingActions.isEmpty()) {
            ArrayList<String> actions = new ArrayList<String>(pendingActions);
            pendingActions.clear();
            for (int i = 0; i < actions.size(); i++) {
                handleAction(actions.get(i));
            }
        }

        // ---- notes: live tab name ----
        if (nameCheckTimer > 0f) {
            nameCheckTimer -= amount;
            if (nameCheckTimer <= 0f && viewMode > 0 && viewMode - 1 < notes.size()) {
                String id = "tab" + viewMode;
                String want = nameOf(notes.get(viewMode - 1));
                for (int i = 0; i < buttons.size(); i++) {
                    Btn b = buttons.get(i);
                    if (b.id.equals(id) && !b.text.equals(want)) rebuildNeeded = true;
                }
            }
        }
        if (noteLabel != null) {
            caretTimer += amount;
            if (caretTimer >= 0.5f) {
                caretTimer = 0f;
                caretOn = !caretOn;
                refreshNoteLabel();
            }
        }

        if (rebuildNeeded) {
            rebuildNeeded = false;
            rebuildUI();
        }

        // ---- status line: message > hovered button tip > hovered system > hint ----
        if (status != null) {
            String desired;
            if (messageTimer > 0f) {
                messageTimer -= amount;
                desired = wantedStatus;
            } else {
                Btn hb = buttonAt(mx, my);
                if (hb != null && hb.tip != null) {
                    desired = hb.tip;
                } else {
                    String hover = hoveredSystemName();
                    desired = hover != null ? hover : defaultHint();
                }
            }
            if (!desired.equals(statusText)) {
                statusText = desired;
                status.setText(statusText);
            }
        }
    }

    private String hoveredSystemName() {
        if (!mouseIn || !viewReady || pos == null) return null;
        float best = 11f * 11f;
        String name = null;
        for (int i = 0; i < dots.size(); i++) {
            Dot d = dots.get(i);
            float d2 = dist2(toSX(d.x), toSY(d.y), mx, my);
            if (d2 <= best) {
                best = d2;
                name = d.entered ? d.name : d.name + "  (not visited)";
            }
        }
        if (name == null && abyssTag != null) {
            try {
                if (Misc.isInAbyss(new Vector2f(toWX(mx), toWY(my)))) name = "The Abyss";
            } catch (Throwable t) {
                // ignore
            }
        }
        return name;
    }

    // =====================================================================
    // Rendering
    //   renderBelow : strip, tool column, the whole map, note view, delete dialog (under the text labels)
    //   render      : pop-out panels and the delete X (above everything)
    // =====================================================================

    private void beginGL() {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_POINT_SMOOTH);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    public void renderBelow(float alphaMult) {
        if (pos == null) return;
        ensureView();
        beginGL();

        quad(pos.getX(), pos.getY(), SIDE_W, pos.getHeight(), 0.02f, 0.03f, 0.06f, 0.55f * alphaMult);

        if (viewMode == 0) {
            quad(sx(CONTENT_X), pos.getY(), TOOL_W, pos.getHeight(), 0.02f, 0.03f, 0.06f, 0.6f * alphaMult);
            if (viewReady && pendingDelete < 0) renderMap(alphaMult);
        } else {
            float x = sx(CONTENT_X);
            float w = pos.getWidth() - CONTENT_X;
            quad(x, pos.getY(), w, pos.getHeight(), 0.02f, 0.03f, 0.06f, 0.8f * alphaMult);
            setColor(Misc.getDarkPlayerColor(), 0.9f * alphaMult);
            GL11.glLineWidth(1f);
            outline(x, pos.getY(), w, pos.getHeight() - 28f);
            if (noteView != null && editor != null && editorFor == viewMode && pendingDelete < 0) {
                editor.setSize(edW(), edH());
                float el = sx(edLeftLocal());
                float et = sy(edTopLocal());
                float sc = Global.getSettings().getScreenScaleMult();
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor((int) ((el - 4f) * sc), (int) ((et - edH()) * sc), (int) Math.ceil((edW() + 10f) * sc), (int) Math.ceil((edH() + 2f) * sc));
                try {
                    noteView.render(editor, el, et, edW(), edH(), alphaMult, caretOn, true,
                            Misc.getHighlightColor(), Misc.getTextColor(), Misc.getBasePlayerColor(), Misc.getGrayColor());
                } catch (Throwable t) {
                    // never let text drawing break the screen
                }
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_TEXTURE_2D);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            }
        }

        if (pendingDelete >= 0) {
            quad(pos.getX(), pos.getY(), pos.getWidth(), pos.getHeight(), 0f, 0f, 0f, 0.55f * alphaMult);
            float dw = 320f;
            float dh = 90f;
            float left = sx(dlgX(dw));
            float top = sy(dlgY(dh));
            quad(left, top - dh, dw, dh, 0.05f, 0.06f, 0.1f, 0.98f * alphaMult);
            setColor(Misc.getBasePlayerColor(), alphaMult);
            GL11.glLineWidth(1.5f);
            outline(left, top - dh, dw, dh);
        }

        for (int i = 0; i < buttons.size(); i++) {
            Btn b = buttons.get(i);
            if (pendingDelete >= 0 && !b.id.startsWith("confirm")) continue;
            drawButton(b, alphaMult);
        }

        GL11.glPopAttrib();
    }

    public void render(float alphaMult) {
        if (pos == null) return;
        beginGL();

        if (flyRect != null && pendingDelete < 0) {
            float left = sx(flyRect[0]);
            float top = sy(flyRect[1]);
            quad(left, top - flyRect[3], flyRect[2], flyRect[3], 0.03f, 0.04f, 0.08f, 0.96f * alphaMult);
            setColor(Misc.getBasePlayerColor(), 0.9f * alphaMult);
            GL11.glLineWidth(1.5f);
            outline(left, top - flyRect[3], flyRect[2], flyRect[3]);
            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glVertex2f(left, top - 10f);
            GL11.glVertex2f(left - 7f, top - 17f);
            GL11.glVertex2f(left, top - 24f);
            GL11.glEnd();
            for (int i = 0; i < flyBtns.size(); i++) {
                drawButton(flyBtns.get(i), alphaMult);
            }
        }

        if (pendingDelete < 0) {
            int t = noteTabAt(mx, my);
            if (t > 0) {
                float left = pos.getX() + SIDE_W - X_ZONE;
                float b = tabBottomScreen(t);
                float cx = left + X_ZONE * 0.5f;
                float cy = b + TAB_H * 0.5f;
                if (inDeleteZone(mx, my, t)) {
                    quad(left, b, X_ZONE, TAB_H, 0.8f, 0.15f, 0.15f, 0.45f * alphaMult);
                    GL11.glColor4f(1f, 0.5f, 0.5f, alphaMult);
                } else {
                    GL11.glColor4f(0.85f, 0.85f, 0.9f, 0.85f * alphaMult);
                }
                GL11.glLineWidth(2f);
                xMark(cx, cy, 5f);
            }
        }

        GL11.glPopAttrib();
    }

    /** The amber CRT map. Everything is clipped to the map rectangle. */
    private void renderMap(float a) {
        float x = mapL();
        float y = mapB();
        float w = mapW();
        float h = mapH();

        float sc = Global.getSettings().getScreenScaleMult();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * sc), (int) (y * sc), (int) Math.ceil(w * sc), (int) Math.ceil(h * sc));

        boolean glitching = glitchTime > 0f;
        if (glitching && glitchJolt != 0f) {
            GL11.glPushMatrix();
            GL11.glTranslatef(0f, glitchJolt, 0f);
            renderMapContent(x, y, w, h, a);
            GL11.glPopMatrix();
        } else {
            renderMapContent(x, y, w, h, a);
        }
        if (glitching) drawGlitch(x, y, w, h, a, sc);

        drawCrtEffects(x, y, w, h, a);

        // band behind the status text
        quad(x, y + h - STATUS_H, w, STATUS_H, 0.03f, 0.016f, 0.004f, 0.75f * a);
        setColor(AMBER_DIM, 0.5f * a);
        GL11.glLineWidth(1f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x, y + h - STATUS_H);
        GL11.glVertex2f(x + w, y + h - STATUS_H);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // bezel
        setColor(AMBER_DIM, 0.85f * a);
        GL11.glLineWidth(2f);
        outline(x, y, w, h);
    }

    /** Everything on the map screen except the CRT overlay. */
    private void renderMapContent(float x, float y, float w, float h, float a) {
        // phosphor screen: dark warm base with a faint glow toward the middle
        quad(x - 60f, y - 60f, w + 120f, h + 120f, 0.045f, 0.026f, 0.010f, a);
        softEllipse(x + w * 0.5f, y + h * 0.5f, w * 0.75f, h * 0.75f, 0.20f, 0.11f, 0.035f, 0.55f * a, 0f, 40);

        drawAbyss(a);
        drawGrid(a);
        drawSlipstreams(a);
        drawSystemDots(a);

        // all text in one pass (LazyLib switches textures on), then back to plain geometry
        drawMapText(a);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        drawPlayer(a);
        drawStrokes(a);
        drawSymbols(a);
        drawUserText(a);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        drawCursor(a);
    }

    /**
     * Abyss interference: horizontal slices of the picture torn sideways, a red/cyan colour split on
     * the tear edges, static in the torn bands and a brief roll. Runs for a fraction of a second every
     * few seconds while the fleet is in the Abyss.
     */
    private void drawGlitch(float x, float y, float w, float h, float a, float sc) {
        for (int i = 0; i < glitchSlices.length; i++) {
            float[] sl = glitchSlices[i];
            float sy0 = y + sl[0];
            float sh = sl[1];
            float dx = sl[2];
            if (sy0 > y + h || sy0 + sh < y) continue;
            float cy0 = Math.max(y, sy0), cy1 = Math.min(y + h, sy0 + sh);
            GL11.glScissor((int) (x * sc), (int) (cy0 * sc), (int) Math.ceil(w * sc), (int) Math.ceil((cy1 - cy0) * sc));
            GL11.glPushMatrix();
            GL11.glTranslatef(dx, glitchJolt, 0f);
            renderMapContent(x, y, w, h, a);
            GL11.glPopMatrix();
            // colour split on the band
            GL11.glColor4f(1f, 0.1f, 0.05f, 0.12f * a);
            fillRect(x, cy0, w, cy1 - cy0);
            GL11.glColor4f(0.2f, 0.9f, 1f, 0.35f * a);
            GL11.glLineWidth(1f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(x, cy0 + 0.5f);
            GL11.glVertex2f(x + w, cy0 + 0.5f);
            GL11.glColor4f(1f, 0.2f, 0.1f, 0.45f * a);
            GL11.glVertex2f(x, cy1 - 0.5f);
            GL11.glVertex2f(x + w, cy1 - 0.5f);
            GL11.glEnd();
            // static
            GL11.glBegin(GL11.GL_LINES);
            int n = (int) (w * (cy1 - cy0) / 900f) + 6;
            for (int k = 0; k < n; k++) {
                float px = x + noise.nextFloat() * w;
                float py = cy0 + noise.nextFloat() * (cy1 - cy0);
                float len = 2f + noise.nextFloat() * 14f;
                GL11.glColor4f(1f, 0.75f, 0.45f, (0.15f + noise.nextFloat() * 0.45f) * a);
                GL11.glVertex2f(px, py);
                GL11.glVertex2f(px + len, py);
            }
            GL11.glEnd();
        }
        GL11.glScissor((int) (x * sc), (int) (y * sc), (int) Math.ceil(w * sc), (int) Math.ceil(h * sc));
        // a quick brightness pop at the start of each burst
        if (glitchTime > 0.22f) {
            GL11.glColor4f(1f, 0.55f, 0.25f, 0.06f * a);
            fillRect(x, y, w, h);
        }
    }

    private void updateGlitch(float amount) {
        abyssCheckTimer -= amount;
        if (abyssCheckTimer <= 0f) {
            abyssCheckTimer = 0.5f;
            playerInAbyss = false;
            try {
                if (Global.getSector().getPlayerFleet() != null) {
                    Vector2f p = Global.getSector().getPlayerFleet().getLocationInHyperspace();
                    playerInAbyss = p != null && Misc.getAbyssalDepth(p) > 0f;
                }
            } catch (Throwable t) {
                playerInAbyss = false;
            }
        }
        if (glitchTime > 0f) {
            glitchTime -= amount;
            glitchReseed -= amount;
            if (glitchReseed <= 0f) {
                reseedGlitch();
                glitchReseed = 0.05f + noise.nextFloat() * 0.05f;
            }
            if (glitchTime <= 0f) {
                glitchTime = 0f;
                glitchSlices = new float[0][];
                glitchJolt = 0f;
            }
            return;
        }
        if (!playerInAbyss || viewMode != 0) return;
        glitchCooldown -= amount;
        if (glitchCooldown <= 0f) {
            glitchCooldown = 2f + noise.nextFloat() * 3.5f;
            glitchTime = 0.18f + noise.nextFloat() * 0.3f;
            glitchReseed = 0f;
        }
    }

    private void reseedGlitch() {
        if (pos == null) return;
        float h = mapH();
        int n = 2 + noise.nextInt(4);
        glitchSlices = new float[n][];
        for (int i = 0; i < n; i++) {
            float sh = 3f + noise.nextFloat() * noise.nextFloat() * 60f;
            float dx = (noise.nextBoolean() ? 1f : -1f) * (6f + noise.nextFloat() * 40f);
            glitchSlices[i] = new float[]{noise.nextFloat() * (h - sh), sh, dx};
        }
        glitchJolt = noise.nextFloat() < 0.35f ? (noise.nextFloat() - 0.5f) * 14f : 0f;
    }

    // ---------- map layers ----------

    private void drawAbyss(float a) {
        if (abyssBlobs.isEmpty()) return;
        float s = scale();
        for (int i = 0; i < abyssBlobs.size(); i++) {
            float[] b = abyssBlobs.get(i);
            float px = toSX(b[0]);
            float py = toSY(b[1]);
            float r = b[2] * s;
            if (!onScreen(px, py, r)) continue;
            float pulse = 0.82f + 0.18f * (float) Math.sin(time * 0.5f + b[4]);
            float alpha = (0.10f + 0.20f * b[3]) * pulse * a;
            softCircle(px, py, r, ABYSS.getRed() / 255f, ABYSS.getGreen() / 255f, ABYSS.getBlue() / 255f, alpha, 0f, 18);
        }
    }

    private void drawGrid(float a) {
        float s = scale();
        float x0 = toWX(mapL()), x1 = toWX(mapL() + mapW());
        float y0 = toWY(mapB()), y1 = toWY(mapB() + mapH());

        float minorPx = GRID_MINOR * s;
        float minorAlpha = Math.max(0f, Math.min(1f, (minorPx - 14f) / 30f)) * 0.10f;
        GL11.glLineWidth(1f);
        if (minorAlpha > 0.005f) {
            setColor(AMBER, minorAlpha * a);
            gridLines(GRID_MINOR, x0, x1, y0, y1);
        }
        float majorPx = GRID_MAJOR * s;
        float majorAlpha = Math.max(0.10f, Math.min(1f, (majorPx - 10f) / 40f)) * 0.17f;
        setColor(AMBER, majorAlpha * 0.35f * a); // glow pass
        GL11.glLineWidth(3f);
        gridLines(GRID_MAJOR, x0, x1, y0, y1);
        setColor(AMBER, majorAlpha * a);
        GL11.glLineWidth(1f);
        gridLines(GRID_MAJOR, x0, x1, y0, y1);
    }

    /** Dash-dot grid lines; the pattern is anchored to the map so it doesn't crawl while panning. */
    private void gridLines(float step, float x0, float x1, float y0, float y1) {
        final float dash = 12f, gap = 4f, dot = 2f;
        final float period = dash + gap + dot + gap;
        float phaseY = ((toSY(secMinY) - mapB()) % period + period) % period;
        float phaseX = ((toSX(secMinX) - mapL()) % period + period) % period;
        GL11.glBegin(GL11.GL_LINES);
        for (float gx = (float) Math.floor((x0 - secMinX) / step) * step + secMinX; gx <= x1; gx += step) {
            float px = toSX(gx);
            for (float y = mapB() + phaseY - period; y < mapB() + mapH(); y += period) {
                emitDash(px, y, px, y + dash);
                emitDash(px, y + dash + gap, px, y + dash + gap + dot);
            }
        }
        for (float gy = (float) Math.floor((y0 - secMinY) / step) * step + secMinY; gy <= y1; gy += step) {
            float py = toSY(gy);
            for (float x = mapL() + phaseX - period; x < mapL() + mapW(); x += period) {
                emitDash(x, py, x + dash, py);
                emitDash(x + dash + gap, py, x + dash + gap + dot, py);
            }
        }
        GL11.glEnd();
    }

    private void emitDash(float ax, float ay, float bx, float by) {
        GL11.glVertex2f(ax, ay);
        GL11.glVertex2f(bx, by);
    }

    private void drawSlipstreams(float a) {
        if (streams.isEmpty()) return;
        float s = scale();
        for (int i = 0; i < streams.size(); i++) {
            ArrayList<float[]> run = streams.get(i);
            // body: a band as wide as the stream, plus a brighter core line
            GL11.glBegin(GL11.GL_QUADS);
            for (int j = 0; j + 1 < run.size(); j++) {
                float[] p = run.get(j);
                float[] q = run.get(j + 1);
                float ax = toSX(p[0]), ay = toSY(p[1]);
                float bx = toSX(q[0]), by = toSY(q[1]);
                float hwA = Math.max(1.5f, p[2] * s);
                float hwB = Math.max(1.5f, q[2] * s);
                if (!onScreen(ax, ay, hwA + 50f) && !onScreen(bx, by, hwB + 50f)) continue;
                float dx = bx - ax, dy = by - ay;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.01f) continue;
                float nx = -dy / len, ny = dx / len;
                float al = 0.13f * Math.min(p[3], q[3]) * a;
                GL11.glColor4f(STREAM.getRed() / 255f, STREAM.getGreen() / 255f, STREAM.getBlue() / 255f, al);
                GL11.glVertex2f(ax + nx * hwA, ay + ny * hwA);
                GL11.glVertex2f(ax - nx * hwA, ay - ny * hwA);
                GL11.glVertex2f(bx - nx * hwB, by - ny * hwB);
                GL11.glVertex2f(bx + nx * hwB, by + ny * hwB);
            }
            GL11.glEnd();

            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int j = 0; j < run.size(); j++) {
                float[] p = run.get(j);
                GL11.glColor4f(STREAM.getRed() / 255f, STREAM.getGreen() / 255f, STREAM.getBlue() / 255f, 0.45f * p[3] * a);
                GL11.glVertex2f(toSX(p[0]), toSY(p[1]));
            }
            GL11.glEnd();

            // flow: bright motes drifting along the stream's direction
            float spacing = Math.max(1800f, 26f / s);
            float offset = (time * 1200f) % spacing;
            float along = 0f;
            float pointSize = Math.max(2f, Math.min(5f, 3000f * s * 0.02f));
            GL11.glPointSize(pointSize);
            GL11.glBegin(GL11.GL_POINTS);
            for (int j = 0; j + 1 < run.size(); j++) {
                float[] p = run.get(j);
                float[] q = run.get(j + 1);
                float dx = q[0] - p[0], dy = q[1] - p[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.01f) continue;
                float first = offset - (along % spacing);
                if (first < 0f) first += spacing;
                for (float d = first; d < len; d += spacing) {
                    float t = d / len;
                    float px = toSX(p[0] + dx * t), py = toSY(p[1] + dy * t);
                    if (!onScreen(px, py, 4f)) continue;
                    GL11.glColor4f(1f, 0.95f, 0.85f, 0.85f * p[3] * a);
                    GL11.glVertex2f(px, py);
                }
                along += len;
            }
            GL11.glEnd();
        }
    }

    private void drawSystemDots(float a) {
        for (int i = 0; i < dots.size(); i++) {
            Dot d = dots.get(i);
            float px = toSX(d.x);
            float py = toSY(d.y);
            if (!onScreen(px, py, 20f)) continue;
            if (d.entered) {
                softCircle(px, py, 10f, 1f, 0.66f, 0.22f, 0.35f * a, 0f, 16);
                setColor(AMBER_HOT, a);
                fillCircle(px, py, 3.2f, 12);
            } else {
                softCircle(px, py, 6f, 0.7f, 0.4f, 0.13f, 0.25f * a, 0f, 12);
                setColor(AMBER_DIM, 0.9f * a);
                fillCircle(px, py, 2f, 8);
            }
        }
    }

    private void drawMapText(float a) {
        if (labels == null) return;
        float s = scale();

        // grid labels on the major lines: columns A, B, C... along the top, rows 1, 2, 3... down the left
        float majorPx = GRID_MAJOR * s;
        if (majorPx > 45f) {
            float x0 = toWX(mapL()), x1 = toWX(mapL() + mapW());
            float y0 = toWY(mapB()), y1 = toWY(mapB() + mapH() - STATUS_H);
            float ga = 0.55f * a;
            for (float gx = (float) Math.floor((x0 - secMinX) / GRID_MAJOR) * GRID_MAJOR + secMinX; gx <= x1; gx += GRID_MAJOR) {
                int col = Math.round((gx - secMinX) / GRID_MAJOR);
                drawLabel(label(colName(col), MapLabels.SMALL, AMBER), toSX(gx + GRID_MAJOR * 0.5f), mapB() + mapH() - STATUS_H - 10f, ga);
            }
            for (float gy = (float) Math.floor((y0 - secMinY) / GRID_MAJOR) * GRID_MAJOR + secMinY; gy <= y1; gy += GRID_MAJOR) {
                int row = Math.round((secMaxY - gy) / GRID_MAJOR);
                drawLabel(label(String.valueOf(row), MapLabels.SMALL, AMBER), mapL() + 12f, toSY(gy - GRID_MAJOR * 0.5f), ga);
            }
        }

        // the Abyss
        if (abyssTag != null && abyssVisible()) {
            float px = toSX(abyssTag.x), py = toSY(abyssTag.y);
            // keep the name inside the bottom-left of the screen while any of the Abyss is visible
            float hw = 110f;
            try {
                hw = labels.width(abyssTag.labelId) * 0.5f;
            } catch (Throwable t) {
                // default
            }
            px = Math.max(mapL() + hw + 14f, Math.min(mapL() + mapW() - hw - 14f, px));
            py = Math.max(mapB() + 26f, Math.min(mapB() + mapH() - STATUS_H - 26f, py));
            drawLabel(abyssTag.labelId, px, py, 0.8f * a);
        }

        // constellations: strongest when zoomed out, fading as system names take over
        float conAlpha = Math.max(0.18f, Math.min(0.8f, 1.1f - (zoom - 1f) * 0.25f));
        for (int i = 0; i < constellations.size(); i++) {
            NameTag n = constellations.get(i);
            float px = toSX(n.x), py = toSY(n.y);
            if (!onScreen(px, py, 200f)) continue;
            drawLabel(n.labelId, px, py, conAlpha * a);
        }

        // star systems: appear as you zoom in
        float sysAlpha = Math.max(0f, Math.min(1f, (zoom - 1.6f) / 1.2f));
        if (sysAlpha > 0.02f) {
            for (int i = 0; i < dots.size(); i++) {
                Dot d = dots.get(i);
                float px = toSX(d.x), py = toSY(d.y);
                if (!onScreen(px, py, 80f)) continue;
                drawLabel(d.labelId, px, py - 11f, sysAlpha * (d.entered ? 1f : 0.7f) * a);
            }
        }
    }

    private void drawUserText(float a) {
        if (texts.isEmpty()) return;
        if (labels != null) {
            for (int i = 0; i < texts.size(); i++) {
                MapText t = texts.get(i);
                float px = toSX(t.x), py = toSY(t.y);
                if (!onScreen(px, py, 600f)) continue;
                String shown = t.text.length() == 0 ? " " : t.text;
                try {
                    if (t.labelId < 0 || t.shownColor != t.color || t.shownSize != t.size) {
                        int c = Math.max(0, Math.min(COLORS.length - 1, t.color));
                        t.labelId = labels.add(shown, MapLabels.TEXT_S + t.size, COLORS[c], false);
                        t.shownColor = t.color;
                        t.shownSize = t.size;
                        t.shownText = shown;
                    } else if (!shown.equals(t.shownText)) {
                        labels.setText(t.labelId, shown);
                        t.shownText = shown;
                    }
                    labels.draw(t.labelId, px, py, a);
                } catch (Throwable ex) {
                    // skip a broken label
                }
            }
        }
        // editing frame + caret
        if (editingText != null) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            float[] b = textBounds(editingText);
            Color c = COLORS[Math.max(0, Math.min(COLORS.length - 1, editingText.color))];
            setColor(c, 0.55f * a);
            GL11.glLineWidth(1f);
            dashedRect(b[0] - 2f, b[1] - 2f, b[2] + 2f, b[3] + 2f);
            if (caretOn && labels != null) {
                int kind = MapLabels.TEXT_S + editingText.size;
                String[] ls = editingText.text.split("\n", -1);
                float lh = labels.lineHeight(kind);
                float cx = toSX(editingText.x) + labels.measure(kind, ls[ls.length - 1]) + 2f;
                float top = toSY(editingText.y) - (ls.length - 1) * lh;
                setColor(c, a);
                GL11.glLineWidth(2f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(cx, top - 2f);
                GL11.glVertex2f(cx, top - lh + 1f);
                GL11.glEnd();
            }
        }
    }

    private void dashedRect(float x0, float y0, float x1, float y1) {
        GL11.glBegin(GL11.GL_LINES);
        for (float x = x0; x < x1; x += 8f) {
            GL11.glVertex2f(x, y0);
            GL11.glVertex2f(Math.min(x1, x + 4f), y0);
            GL11.glVertex2f(x, y1);
            GL11.glVertex2f(Math.min(x1, x + 4f), y1);
        }
        for (float y = y0; y < y1; y += 8f) {
            GL11.glVertex2f(x0, y);
            GL11.glVertex2f(x0, Math.min(y1, y + 4f));
            GL11.glVertex2f(x1, y);
            GL11.glVertex2f(x1, Math.min(y1, y + 4f));
        }
        GL11.glEnd();
    }

    private boolean abyssVisible() {
        float s = scale();
        for (int i = 0; i < abyssBlobs.size(); i += 3) {
            float[] b = abyssBlobs.get(i);
            if (b[3] > 0.2f && onScreen(toSX(b[0]), toSY(b[1]), -10f)) return true;
        }
        return false;
    }

    private static String colName(int i) {
        if (i < 0) return "-";
        String s = "";
        do {
            s = (char) ('A' + (i % 26)) + s;
            i = i / 26 - 1;
        } while (i >= 0);
        return s;
    }

    /** The fleet: a pulsing amber ring with a crosshair. */
    private void drawPlayer(float a) {
        try {
            if (Global.getSector().getPlayerFleet() == null) return;
            Vector2f p = Global.getSector().getPlayerFleet().getLocationInHyperspace();
            if (p == null) return;
            float px = toSX(p.x);
            float py = toSY(p.y);
            float pulse = 0.6f + 0.4f * (float) Math.sin(time * 4f);
            softCircle(px, py, 16f, 1f, 0.85f, 0.55f, 0.3f * pulse * a, 0f, 18);
            setColor(AMBER_HOT, (0.6f + 0.4f * pulse) * a);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 24; i++) {
                double ang = i * Math.PI * 2.0 / 24.0;
                GL11.glVertex2f(px + (float) Math.cos(ang) * 8f, py + (float) Math.sin(ang) * 8f);
            }
            GL11.glEnd();
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(px - 13f, py);
            GL11.glVertex2f(px - 4f, py);
            GL11.glVertex2f(px + 4f, py);
            GL11.glVertex2f(px + 13f, py);
            GL11.glVertex2f(px, py - 13f);
            GL11.glVertex2f(px, py - 4f);
            GL11.glVertex2f(px, py + 4f);
            GL11.glVertex2f(px, py + 13f);
            GL11.glEnd();
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Scanlines, a slow rolling bright band, edge vignette, flicker and phosphor speckle. */
    private void drawCrtEffects(float x, float y, float w, float h, float a) {
        // scanlines
        GL11.glLineWidth(1f);
        GL11.glColor4f(0f, 0f, 0f, 0.28f * a);
        GL11.glBegin(GL11.GL_LINES);
        for (float ly = y + 1f; ly < y + h; ly += 3f) {
            GL11.glVertex2f(x, ly);
            GL11.glVertex2f(x + w, ly);
        }
        GL11.glEnd();

        // rolling band
        float bandH = 140f;
        float period = h + bandH;
        float by = y + h - ((time * 55f) % period);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1f, 0.7f, 0.3f, 0f);
        GL11.glVertex2f(x, by);
        GL11.glVertex2f(x + w, by);
        GL11.glColor4f(1f, 0.7f, 0.3f, 0.045f * a);
        GL11.glVertex2f(x + w, by - bandH * 0.5f);
        GL11.glVertex2f(x, by - bandH * 0.5f);
        GL11.glVertex2f(x, by - bandH * 0.5f);
        GL11.glVertex2f(x + w, by - bandH * 0.5f);
        GL11.glColor4f(1f, 0.7f, 0.3f, 0f);
        GL11.glVertex2f(x + w, by - bandH);
        GL11.glVertex2f(x, by - bandH);
        GL11.glEnd();

        // vignette: darker toward the edges, like a curved tube
        float vx = Math.min(w, h) * 0.22f;
        edgeShade(x, y, w, vx, 0.62f * a, true);
        edgeShade(x, y + h - vx, w, vx, 0.62f * a, false);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0f, 0f, 0f, 0.62f * a);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glColor4f(0f, 0f, 0f, 0f);
        GL11.glVertex2f(x + vx, y + h);
        GL11.glVertex2f(x + vx, y);
        GL11.glColor4f(0f, 0f, 0f, 0f);
        GL11.glVertex2f(x + w - vx, y);
        GL11.glVertex2f(x + w - vx, y + h);
        GL11.glColor4f(0f, 0f, 0f, 0.62f * a);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();

        // flicker
        float f = noise.nextFloat() * 0.035f;
        GL11.glColor4f(0f, 0f, 0f, f * a);
        fillRect(x, y, w, h);

        // phosphor speckle
        GL11.glPointSize(1.5f);
        GL11.glBegin(GL11.GL_POINTS);
        for (int i = 0; i < 40; i++) {
            GL11.glColor4f(1f, 0.7f, 0.3f, (0.05f + noise.nextFloat() * 0.12f) * a);
            GL11.glVertex2f(x + noise.nextFloat() * w, y + noise.nextFloat() * h);
        }
        GL11.glEnd();
    }

    /** Horizontal shade band: dark at the outer edge, clear toward the middle. */
    private void edgeShade(float x, float y, float w, float hgt, float maxA, boolean bottom) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0f, 0f, 0f, bottom ? maxA : 0f);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glColor4f(0f, 0f, 0f, bottom ? 0f : maxA);
        GL11.glVertex2f(x + w, y + hgt);
        GL11.glVertex2f(x, y + hgt);
        GL11.glEnd();
    }

    private void softCircle(float cx, float cy, float r, float red, float green, float blue, float aIn, float aOut, int seg) {
        softEllipse(cx, cy, r, r, red, green, blue, aIn, aOut, seg);
    }

    private void softEllipse(float cx, float cy, float rx, float ry, float red, float green, float blue, float aIn, float aOut, int seg) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(red, green, blue, aIn);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(red, green, blue, aOut);
        for (int i = 0; i <= seg; i++) {
            double ang = i * Math.PI * 2.0 / seg;
            GL11.glVertex2f(cx + (float) Math.cos(ang) * rx, cy + (float) Math.sin(ang) * ry);
        }
        GL11.glEnd();
    }

    // ---------- buttons ----------

    private void drawButton(Btn b, float alpha) {
        float left = sx(b.x);
        float top = sy(b.y);
        float bottom = top - b.h;
        boolean sel = isSelected(b);
        boolean hov = over(b, mx, my);
        Color base = b.danger ? new Color(225, 85, 85) : Misc.getBasePlayerColor();

        if (b.icon == IC_SWATCH) {
            // the swatch itself is the button
            setColor(b.accent, (hov || sel ? 1f : 0.85f) * alpha);
            fillRect(left + 3f, bottom + 3f, b.w - 6f, b.h - 6f);
            if (sel) {
                GL11.glColor4f(1f, 1f, 1f, alpha);
                GL11.glLineWidth(2f);
                outline(left, bottom, b.w, b.h);
            } else if (hov) {
                GL11.glColor4f(1f, 1f, 1f, 0.5f * alpha);
                GL11.glLineWidth(1f);
                outline(left, bottom, b.w, b.h);
            }
            return;
        }

        float fill = sel ? 0.42f : (hov ? 0.24f : 0.1f);
        if (b.text == null && !sel && !hov) fill = 0.06f;
        setColor(base, fill * alpha);
        fillRect(left, bottom, b.w, b.h);
        setColor(base, (sel ? 1f : (hov ? 0.85f : 0.45f)) * alpha);
        GL11.glLineWidth(sel ? 2f : 1f);
        outline(left, bottom, b.w, b.h);

        if (b.icon != IC_TEXT) {
            float cx = left + b.w * 0.5f;
            float cy = bottom + b.h * 0.5f;
            float s = b.w * 0.5f;
            Color ink = sel || hov ? new Color(235, 245, 255) : new Color(190, 205, 220);
            drawIcon(b, cx, cy, s, ink, alpha);
        }
    }

    private void drawIcon(Btn b, float cx, float cy, float s, Color ink, float a) {
        setColor(ink, a);
        float u = s / 18f; // icon unit: designs below assume an 18-unit half-size
        switch (b.icon) {
            case IC_PEN: {
                // pencil, tip at lower-left, tip in the current pen color
                GL11.glLineWidth(2f);
                float[][] body = {{-5, -9}, {9, 5}, {5, 9}, {-9, -5}};
                lineLoop(cx, cy, u, body);
                line(cx, cy, u, 6, 2, 2, 6);
                setColor(COLORS[colorIdx], a);
                GL11.glBegin(GL11.GL_TRIANGLES);
                GL11.glVertex2f(cx - 5 * u, cy - 9 * u);
                GL11.glVertex2f(cx - 9 * u, cy - 5 * u);
                GL11.glVertex2f(cx - 12 * u, cy - 12 * u);
                GL11.glEnd();
                // thickness hint
                GL11.glPointSize(SIZES[sizeIdx] * 0.8f + 1f);
                GL11.glBegin(GL11.GL_POINTS);
                GL11.glVertex2f(cx + 10 * u, cy - 11 * u);
                GL11.glEnd();
                break;
            }
            case IC_ERASER: {
                // slanted eraser block: pink tip, light body, line underneath
                GL11.glColor4f(0.95f, 0.55f, 0.65f, a);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(cx - 11 * u, cy - 3 * u);
                GL11.glVertex2f(cx - 4 * u, cy - 10 * u);
                GL11.glVertex2f(cx + 1 * u, cy - 5 * u);
                GL11.glVertex2f(cx - 6 * u, cy + 2 * u);
                GL11.glEnd();
                setColor(ink, a);
                GL11.glLineWidth(2f);
                float[][] blk = {{-11, -3}, {-4, -10}, {11, 5}, {4, 12}};
                lineLoop(cx, cy, u, blk);
                line(cx, cy, u, -6, 2, 1, -5);
                line(cx, cy, u, -12, -13, 12, -13);
                break;
            }
            case IC_SYMBOLS: {
                GL11.glLineWidth(2f);
                star(cx - 3 * u, cy + 3 * u, 9 * u);
                setColor(COLORS[colorIdx], a);
                fillRect(cx + 4 * u, cy - 12 * u, 8 * u, 8 * u);
                break;
            }
            case IC_MOVE: {
                GL11.glLineWidth(2f);
                line(cx, cy, u, -12, 0, 12, 0);
                line(cx, cy, u, 0, -12, 0, 12);
                float[][][] heads = {
                        {{-12, 0}, {-8, 4}, {-8, -4}},
                        {{12, 0}, {8, 4}, {8, -4}},
                        {{0, 12}, {4, 8}, {-4, 8}},
                        {{0, -12}, {4, -8}, {-4, -8}}
                };
                GL11.glBegin(GL11.GL_TRIANGLES);
                for (int i = 0; i < heads.length; i++) {
                    for (int j = 0; j < 3; j++) {
                        GL11.glVertex2f(cx + heads[i][j][0] * u, cy + heads[i][j][1] * u);
                    }
                }
                GL11.glEnd();
                break;
            }
            case IC_UNDO: {
                // counter-clockwise arc with an arrowhead at its start
                GL11.glLineWidth(2.5f);
                GL11.glBegin(GL11.GL_LINE_STRIP);
                for (int i = 0; i <= 16; i++) {
                    double ang = Math.toRadians(150 - i * 14.0);
                    GL11.glVertex2f(cx + (float) Math.cos(ang) * 9 * u, cy - 2 * u + (float) Math.sin(ang) * 9 * u);
                }
                GL11.glEnd();
                float hx = cx + (float) Math.cos(Math.toRadians(150)) * 9 * u;
                float hy = cy - 2 * u + (float) Math.sin(Math.toRadians(150)) * 9 * u;
                GL11.glBegin(GL11.GL_TRIANGLES);
                GL11.glVertex2f(hx - 6 * u, hy + 2 * u);
                GL11.glVertex2f(hx + 4 * u, hy + 5 * u);
                GL11.glVertex2f(hx + 1 * u, hy - 5 * u);
                GL11.glEnd();
                break;
            }
            case IC_CLEAR: {
                // trash can
                Color red = new Color(255, 130, 130);
                setColor(b.danger ? red : ink, a);
                GL11.glLineWidth(2f);
                line(cx, cy, u, -10, 7, 10, 7);
                line(cx, cy, u, -3, 10, 3, 10);
                float[][] can = {{-8, 7}, {8, 7}, {6, -12}, {-6, -12}};
                lineLoop(cx, cy, u, can);
                line(cx, cy, u, -3, 3, -2, -8);
                line(cx, cy, u, 3, 3, 2, -8);
                break;
            }
            case IC_SIZE: {
                setColor(COLORS[colorIdx], a);
                fillCircle(cx, cy, SIZES[b.arg] * 0.75f + 1.5f, 16);
                break;
            }
            case IC_SYMBOL: {
                drawSymbol(b.arg, cx, cy, 8f * u * 1.6f, COLORS[colorIdx], a);
                break;
            }
            case IC_CONFIRM: {
                // red trash with a check: "yes, clear"
                GL11.glColor4f(1f, 0.45f, 0.45f, a);
                GL11.glLineWidth(3f);
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2f(cx - 9 * u, cy);
                GL11.glVertex2f(cx - 3 * u, cy - 7 * u);
                GL11.glVertex2f(cx + 10 * u, cy + 8 * u);
                GL11.glEnd();
                break;
            }
            case IC_TEXTTOOL: {
                // a serif "T" with a small caret
                GL11.glLineWidth(2.5f);
                line(cx, cy, u, -9, 10, 9, 10);
                line(cx, cy, u, 0, 10, 0, -10);
                GL11.glLineWidth(1.5f);
                line(cx, cy, u, -4, -10, 4, -10);
                line(cx, cy, u, -9, 10, -9, 7);
                line(cx, cy, u, 9, 10, 9, 7);
                setColor(COLORS[colorIdx], a);
                GL11.glLineWidth(2f);
                line(cx, cy, u, 11, -2, 11, -12);
                break;
            }
            case IC_TSIZE: {
                // "A" growing with the size option
                float k = 0.45f + b.arg * 0.2f;
                setColor(COLORS[colorIdx], a);
                GL11.glLineWidth(1.5f + b.arg * 0.4f);
                line(cx, cy, u, -12 * k, -14 * k, 0, 14 * k);
                line(cx, cy, u, 0, 14 * k, 12 * k, -14 * k);
                line(cx, cy, u, -6 * k, -2 * k, 6 * k, -2 * k);
                break;
            }
            case IC_STYLE: {
                float[] sp = {cx - b.w * 0.36f, cy, cx + b.w * 0.36f, cy};
                setColor(COLORS[colorIdx], a);
                if (b.arg == 0) {
                    GL11.glLineWidth(2.5f);
                    GL11.glBegin(GL11.GL_LINES);
                    GL11.glVertex2f(sp[0], cy);
                    GL11.glVertex2f(sp[2], cy);
                    GL11.glEnd();
                } else {
                    patternedLine(sp, 1.6f, b.arg);
                }
                break;
            }
            case IC_CHECKBOX: {
                GL11.glLineWidth(1.5f);
                float[][] box = {{-9, -9}, {9, -9}, {9, 9}, {-9, 9}};
                lineLoop(cx, cy, u, box);
                setColor(Misc.getHighlightColor(), a);
                GL11.glLineWidth(2.5f);
                GL11.glBegin(GL11.GL_LINE_STRIP);
                GL11.glVertex2f(cx - 6 * u, cy + 0 * u);
                GL11.glVertex2f(cx - 1 * u, cy - 5 * u);
                GL11.glVertex2f(cx + 11 * u, cy + 10 * u);
                GL11.glEnd();
                break;
            }
            default: { // -2: cancel "x"
                GL11.glLineWidth(2.5f);
                xMark(cx, cy, 7f * u * 1.4f);
                break;
            }
        }
    }

    // ---------- GL helpers ----------

    private void line(float cx, float cy, float u, float x1, float y1, float x2, float y2) {
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx + x1 * u, cy + y1 * u);
        GL11.glVertex2f(cx + x2 * u, cy + y2 * u);
        GL11.glEnd();
    }

    private void lineLoop(float cx, float cy, float u, float[][] pts) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < pts.length; i++) {
            GL11.glVertex2f(cx + pts[i][0] * u, cy + pts[i][1] * u);
        }
        GL11.glEnd();
    }

    private void xMark(float cx, float cy, float r) {
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - r, cy - r);
        GL11.glVertex2f(cx + r, cy + r);
        GL11.glVertex2f(cx - r, cy + r);
        GL11.glVertex2f(cx + r, cy - r);
        GL11.glEnd();
    }

    private void star(float cx, float cy, float r) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 2.0 + i * Math.PI / 5.0;
            float rad = (i % 2 == 0) ? r : r * 0.42f;
            GL11.glVertex2f(cx + (float) Math.cos(ang) * rad, cy + (float) Math.sin(ang) * rad);
        }
        GL11.glEnd();
    }

    private void quad(float x, float y, float w, float h, float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        fillRect(x, y, w, h);
    }

    private void fillRect(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private void outline(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private void setColor(Color c, float a) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, a);
    }

    private void fillCircle(float cx, float cy, float r, int seg) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= seg; i++) {
            double a = i * Math.PI * 2.0 / seg;
            GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
    }

    // ---------- drawing ----------

    private void drawStrokes(float alpha) {
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            Color c = COLORS[Math.max(0, Math.min(COLORS.length - 1, s.color))];
            setColor(c, 0.95f * alpha);
            if (s.style != 0) {
                float[] sp = new float[s.pts.size() * 2];
                for (int j = 0; j < s.pts.size(); j++) {
                    sp[j * 2] = toSX(s.pts.get(j)[0]);
                    sp[j * 2 + 1] = toSY(s.pts.get(j)[1]);
                }
                patternedLine(sp, s.width, s.style);
                continue;
            }
            GL11.glLineWidth(s.width);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            for (int j = 0; j < s.pts.size(); j++) {
                float[] p = s.pts.get(j);
                GL11.glVertex2f(toSX(p[0]), toSY(p[1]));
            }
            GL11.glEnd();
            GL11.glPointSize(s.width);
            GL11.glBegin(GL11.GL_POINTS);
            for (int j = 0; j < s.pts.size(); j++) {
                float[] p = s.pts.get(j);
                GL11.glVertex2f(toSX(p[0]), toSY(p[1]));
            }
            GL11.glEnd();
        }
    }

    /**
     * Draws a screen-space polyline as dashes (style 1) or dash-dot (style 2), the pattern scaled to
     * the line width and continuing smoothly around corners.
     */
    private void patternedLine(float[] sp, float width, int style) {
        float dash = 5f * width + 8f;
        float gap = 2.5f * width + 5f;
        float[] pattern = style == 1 ? new float[]{dash, gap} : new float[]{dash, gap, 0f, gap};
        int pi = 0;
        float left = pattern[0];
        ArrayList<float[]> dots = new ArrayList<float[]>();
        GL11.glLineWidth(width);
        GL11.glBegin(GL11.GL_LINES);
        for (int j = 0; j + 3 < sp.length; j += 2) {
            float ax = sp[j], ay = sp[j + 1], bx = sp[j + 2], by = sp[j + 3];
            float dx = bx - ax, dy = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.001f) continue;
            float ux = dx / len, uy = dy / len;
            float t = 0f;
            while (t < len) {
                boolean on = pi % 2 == 0;
                float seg = Math.min(left, len - t);
                if (on && pattern[pi] == 0f) {
                    dots.add(new float[]{ax + ux * t, ay + uy * t});
                } else if (on) {
                    GL11.glVertex2f(ax + ux * t, ay + uy * t);
                    GL11.glVertex2f(ax + ux * (t + seg), ay + uy * (t + seg));
                }
                t += seg;
                left -= seg;
                if (left <= 0.0001f) {
                    pi = (pi + 1) % pattern.length;
                    left = pattern[pi];
                    if (left == 0f) { // a dot: record it and move on
                        dots.add(new float[]{ax + ux * t, ay + uy * t});
                        pi = (pi + 1) % pattern.length;
                        left = pattern[pi];
                    }
                }
            }
        }
        GL11.glEnd();
        GL11.glPointSize(width + 1.5f);
        GL11.glBegin(GL11.GL_POINTS);
        for (int i = 0; i < dots.size(); i++) GL11.glVertex2f(dots.get(i)[0], dots.get(i)[1]);
        GL11.glEnd();
    }

    private void drawSymbols(float alpha) {
        for (int i = 0; i < symbols.size(); i++) {
            Symbol m = symbols.get(i);
            Color c = COLORS[Math.max(0, Math.min(COLORS.length - 1, m.color))];
            drawSymbol(m.type, toSX(m.x), toSY(m.y), 9f, c, 1f * alpha);
        }
    }

    private void drawCursor(float alpha) {
        if (!mouseIn) return;
        if (tool == TOOL_ERASER) {
            GL11.glColor4f(1f, 1f, 1f, 0.8f * alpha);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2.0 / 24.0;
                GL11.glVertex2f(mx + (float) Math.cos(a) * 12f, my + (float) Math.sin(a) * 12f);
            }
            GL11.glEnd();
        } else if (tool == TOOL_SYMBOL) {
            drawSymbol(symIdx, mx, my, 9f, COLORS[colorIdx], 0.45f * alpha);
        } else if (tool == TOOL_PEN && !drawing) {
            setColor(COLORS[colorIdx], 0.7f * alpha);
            GL11.glPointSize(SIZES[sizeIdx]);
            GL11.glBegin(GL11.GL_POINTS);
            GL11.glVertex2f(mx, my);
            GL11.glEnd();
        }
    }

    private void drawSymbol(int type, float cx, float cy, float r, Color c, float a) {
        setColor(c, a);
        switch (type) {
            case 0: // X
                GL11.glLineWidth(3.5f);
                xMark(cx, cy, r);
                break;
            case 1: // square
                setColor(c, a * 0.35f);
                fillRect(cx - r, cy - r, 2f * r, 2f * r);
                setColor(c, a);
                GL11.glLineWidth(2.5f);
                outline(cx - r, cy - r, 2f * r, 2f * r);
                break;
            case 2: // triangle
                setColor(c, a * 0.35f);
                GL11.glBegin(GL11.GL_TRIANGLES);
                GL11.glVertex2f(cx, cy + r * 1.1f);
                GL11.glVertex2f(cx - r, cy - r * 0.8f);
                GL11.glVertex2f(cx + r, cy - r * 0.8f);
                GL11.glEnd();
                setColor(c, a);
                GL11.glLineWidth(2.5f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(cx, cy + r * 1.1f);
                GL11.glVertex2f(cx - r, cy - r * 0.8f);
                GL11.glVertex2f(cx + r, cy - r * 0.8f);
                GL11.glEnd();
                break;
            case 3: // diamond
                setColor(c, a * 0.35f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2f(cx, cy + r * 1.2f);
                GL11.glVertex2f(cx - r * 0.85f, cy);
                GL11.glVertex2f(cx, cy - r * 1.2f);
                GL11.glVertex2f(cx + r * 0.85f, cy);
                GL11.glEnd();
                setColor(c, a);
                GL11.glLineWidth(2.5f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(cx, cy + r * 1.2f);
                GL11.glVertex2f(cx - r * 0.85f, cy);
                GL11.glVertex2f(cx, cy - r * 1.2f);
                GL11.glVertex2f(cx + r * 0.85f, cy);
                GL11.glEnd();
                break;
            case 4: // star
                GL11.glLineWidth(2.5f);
                star(cx, cy, r * 1.15f);
                fillCircle(cx, cy, 2f, 8);
                break;
            default: // ring
                GL11.glLineWidth(3f);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                for (int i = 0; i < 20; i++) {
                    double ang = i * Math.PI * 2.0 / 20.0;
                    GL11.glVertex2f(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r);
                }
                GL11.glEnd();
                fillCircle(cx, cy, 2.2f, 8);
                break;
        }
    }
}
