package starnotes;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.IntelSortTier;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
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
        return "StarNotes";
    }

    /** Laid out like the game's own entries: the title in the faction colour, a grey bullet with the hotkey in yellow. */
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color title = Global.getSector().getPlayerFaction().getBaseUIColor();
        info.addPara(getName(), title, 0f);
        float pad = mode == ListInfoMode.IN_DESC ? 10f : 3f;
        Color tc = mode == ListInfoMode.INTEL ? Misc.getGrayColor() : Misc.getTextColor();
        bullet(info);
        info.addPara("Press %s on the map for full screen", pad, tc, Misc.getHighlightColor(), StarNotesHotkey.getKeyName());
        unindent(info);
    }

    public String getSmallDescriptionTitle() {
        return "StarNotes";
    }

    public String getIcon() {
        return "graphics/icons/intel/log_crosshair.png";
    }

    /** Listed in the Intel screen's Exploration tab rather than a tab of its own. */
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add(Tags.INTEL_EXPLORATION);
        return tags;
    }

    /** The highest sort tier, so StarNotes stays at the top of the Exploration list. */
    public IntelSortTier getSortTier() {
        return IntelSortTier.TIER_0;
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
