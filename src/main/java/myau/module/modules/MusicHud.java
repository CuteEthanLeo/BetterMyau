package myau.module.modules;

import myau.module.Module;
import myau.music.ui.LyricSatellite;
import myau.music.ui.MusicSatellite;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.ui.glass.Island;

/**
 * The music player on the Dynamic Island: a capsule beside the island (cover, live spectrum,
 * a glow in the cover's colour) and the current lyric line under it. Both are satellites, so they
 * never take room from the island itself. Playback settings live in {@link MusicPlayer}.
 */
public class MusicHud extends Module {
    public static final int LYRICS_OFF = 0, LYRICS_BELOW = 1;

    public final ModeProperty side = new ModeProperty("side", 0, new String[]{"LEFT", "RIGHT", "BELOW"});
    public final IntProperty gap = new IntProperty("gap", 6, 2, 30);
    public final BooleanProperty glow = new BooleanProperty("glow", true);
    public final PercentProperty glowStrength = new PercentProperty("glow-strength", 70, this.glow::getValue);
    public final BooleanProperty spectrum = new BooleanProperty("spectrum", true);
    public final BooleanProperty beatBounce = new BooleanProperty("beat-bounce", true);
    public final IntProperty peekSeconds = new IntProperty("peek-seconds", 4, 0, 15);
    public final ModeProperty lyrics = new ModeProperty("lyrics", LYRICS_BELOW, new String[]{"OFF", "BELOW"});
    public final BooleanProperty translation = new BooleanProperty("translation", false, () -> this.lyrics.getValue() != LYRICS_OFF);
    public final BooleanProperty islandAmbient = new BooleanProperty("island-ambient", true);

    public MusicHud() {
        super("MusicHud", true, true);
        Island.addSatellite(new MusicSatellite(this));
        Island.addSatellite(new LyricSatellite(this));
    }
}
