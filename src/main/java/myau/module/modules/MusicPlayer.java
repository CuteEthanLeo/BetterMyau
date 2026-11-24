package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.music.MusicService;
import myau.music.MusicSettings;
import myau.music.model.Quality;
import myau.music.ui.MusicKeys;
import myau.music.ui.MusicScreen;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;

/**
 * The music player's settings, and its key opens the music screen (it never stays "on", like the
 * click GUI). The values live in the normal module config; the NetEase login does not (see
 * {@code MusicStore}). Display settings are in {@link MusicHud}.
 */
public class MusicPlayer extends Module implements MusicSettings {
    public final PercentProperty volume = new PercentProperty("volume", 60);
    public final BooleanProperty followGameVolume = new BooleanProperty("follow-game-volume", true);
    public final ModeProperty quality = new ModeProperty("quality", 2, new String[]{"STANDARD", "HIGHER", "EXHIGH"});
    public final BooleanProperty skipTrial = new BooleanProperty("skip-trial", false);
    public final ModeProperty api = new ModeProperty("api", 0, new String[]{"DIRECT", "CUSTOM"});
    public final TextProperty apiUrl = new TextProperty("api-url", "http://localhost:3000", () -> this.api.getValue() == 1);
    public final TextProperty localFolder = new TextProperty("local-folder", "");

    public MusicPlayer() {
        super("MusicPlayer", false);
        MusicService.get().setSettings(this);
        MusicKeys.init();
    }

    /** The key (or a click in the GUI) opens the player instead of switching a state. */
    @Override
    public boolean toggle() {
        mc.displayGuiScreen(new MusicScreen());
        return true;
    }

    @Override
    public void onEnabled() {
        this.setEnabled(false);
        mc.displayGuiScreen(new MusicScreen());
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) MusicKeys.tick();
    }

    // ------------------------------------------------------------ MusicSettings

    @Override
    public int volume() {
        return this.volume.getValue();
    }

    @Override
    public void setVolume(int volume) {
        this.volume.setValue(Math.max(0, Math.min(100, volume)));
    }

    @Override
    public boolean followGameVolume() {
        return this.followGameVolume.getValue();
    }

    @Override
    public Quality quality() {
        Quality[] all = Quality.values();
        return all[Math.max(0, Math.min(all.length - 1, this.quality.getValue()))];
    }

    @Override
    public boolean skipTrial() {
        return this.skipTrial.getValue();
    }

    @Override
    public boolean customApi() {
        return this.api.getValue() == 1;
    }

    @Override
    public String apiUrl() {
        return this.apiUrl.getValue();
    }

    @Override
    public String localFolder() {
        return this.localFolder.getValue();
    }
}
