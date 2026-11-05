package starnotes;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelManagerAPI;

public class StarNotesModPlugin extends BaseModPlugin {

    public void onGameLoad(boolean newGame) {
        IntelManagerAPI mgr = Global.getSector().getIntelManager();
        if (mgr.getIntel(StarNotesIntel.class).isEmpty()) {
            mgr.addIntel(new StarNotesIntel(), true);
        }
        // transient: re-added every load, never written into the save
        if (!Global.getSector().getListenerManager().hasListenerOfClass(StarNotesHotkey.class)) {
            Global.getSector().getListenerManager().addListener(new StarNotesHotkey(), true);
        }
    }
}
