package starnotes;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.Set;

/**
 * The "StarNotes" entry in the Intel screen. Selecting it shows the drawable sector map.
 */
public class StarNotesIntel extends BaseIntelPlugin {

    public static final String TAG = "StarNotes";

    public StarNotesIntel() {
        setNew(false);
    }

    public String getName() {
        return "StarNotes  (or press " + StarNotesHotkey.getKeyName() + " on the map for full screen)";
    }

    public String getSmallDescriptionTitle() {
        return "StarNotes";
    }

    public String getIcon() {
        return "graphics/icons/intel/log_crosshair.png";
    }

    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(TAG);
        return tags;
    }

    public boolean hasSmallDescription() {
        return false;
    }

    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        info.addPara("A sector map you can draw on and mark with symbols.", Misc.getGrayColor(), 0f);
    }

    public boolean hasLargeDescription() {
        return true;
    }

    public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        StarNotesPanel notes = new StarNotesPanel();
        notes.build(panel, width, height);
    }
}
