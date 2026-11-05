package starnotes;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CustomVisualDialogDelegate;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;

import java.util.Map;

/**
 * Opens StarNotes as a near-full-screen window on top of the campaign map.
 * The game pauses while it is open, like any other dialog.
 */
public class StarNotesDialog implements InteractionDialogPlugin, CustomVisualDialogDelegate {

    private InteractionDialogAPI dialog;
    private StarNotesPanel notesPanel;
    private float w;
    private float h;

    // ---------- InteractionDialogPlugin ----------

    public void init(InteractionDialogAPI dialog) {
        this.dialog = dialog;
        try {
            dialog.hideTextPanel();
        } catch (Throwable t) {
            // older behaviour: harmless if the text panel stays
        }
        w = Global.getSettings().getScreenWidth() - 40f;
        h = Global.getSettings().getScreenHeight() - 40f;
        notesPanel = new StarNotesPanel();
        dialog.showCustomVisualDialog(w, h, this);
    }

    public void optionSelected(String optionText, Object optionData) {
    }

    public void optionMousedOver(String optionText, Object optionData) {
    }

    public void advance(float amount) {
    }

    public void backFromEngagement(EngagementResultAPI battleResult) {
    }

    public Object getContext() {
        return null;
    }

    public Map<String, MemoryAPI> getMemoryMap() {
        return null;
    }

    // ---------- CustomVisualDialogDelegate ----------

    public CustomUIPanelPlugin getCustomPanelPlugin() {
        return notesPanel;
    }

    public void init(CustomPanelAPI panel, final DialogCallbacks callbacks) {
        notesPanel.attach(panel, w, h, new Runnable() {
            public void run() {
                callbacks.dismissDialog();
            }
        });
    }

    public float getNoiseAlpha() {
        return 0f;
    }

    public void reportDismissed(int option) {
        if (dialog != null) dialog.dismiss();
    }
}
