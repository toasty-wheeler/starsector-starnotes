package starnotes;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.listeners.CampaignInputListener;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

import org.lwjgl.input.Keyboard;

/** Opens the full-screen StarNotes window when the hotkey is pressed on the campaign map. */
public class StarNotesHotkey implements CampaignInputListener {

    public static int getKey() {
        try {
            int k = Global.getSettings().getInt("starnotes_hotkey");
            if (k > 0) return k;
        } catch (Throwable t) {
            // setting missing: use the default
        }
        return Keyboard.KEY_N;
    }

    public static String getKeyName() {
        try {
            String n = Keyboard.getKeyName(getKey());
            if (n != null) return n;
        } catch (Throwable t) {
            // fall through
        }
        return "N";
    }

    public int getListenerInputPriority() {
        return 0;
    }

    public void processCampaignInputPreCore(List<InputEventAPI> events) {
        int key = getKey();
        for (int i = 0; i < events.size(); i++) {
            InputEventAPI e = events.get(i);
            if (e.isConsumed() || !e.isKeyDownEvent() || e.isRepeat()) continue;
            if (e.getEventValue() != key) continue;
            if (e.isCtrlDown() || e.isAltDown() || e.isShiftDown()) continue;

            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || ui.getCurrentCoreTab() != null) continue;
            if (Global.getSector().getPlayerFleet() == null) continue;

            e.consume();
            ui.showInteractionDialog(new StarNotesDialog(), Global.getSector().getPlayerFleet());
            return;
        }
    }

    public void processCampaignInputPreFleetControl(List<InputEventAPI> events) {
    }

    public void processCampaignInputPostCore(List<InputEventAPI> events) {
    }
}
