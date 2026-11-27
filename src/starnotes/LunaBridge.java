package starnotes;

import lunalib.lunaSettings.LunaSettings;

/**
 * The only class that touches LunaLib. It is loaded only when LunaLib is enabled
 * (StarNotesHotkey checks first), so StarNotes runs fine without it.
 */
final class LunaBridge {
    private LunaBridge() {
    }

    /** the hotkey set in LunaLib's mod settings, or 0 when unset */
    static int hotkey() {
        Integer k = LunaSettings.getInt("starnotes", "starnotes_hotkey");
        return k == null ? 0 : k.intValue();
    }
}
