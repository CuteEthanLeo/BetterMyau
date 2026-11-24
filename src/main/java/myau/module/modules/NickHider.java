package myau.module.modules;

import myau.enums.ChatColors;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.TextProperty;
import net.minecraft.client.Minecraft;

import java.util.regex.Matcher;

public class NickHider extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final TextProperty protectName = new TextProperty("name", "You");
    public final BooleanProperty scoreboard = new BooleanProperty("scoreboard", true);
    public final BooleanProperty level = new BooleanProperty("level", true);

    public NickHider() {
        super("NickHider", false, true);
    }

    private static final java.util.regex.Pattern SCOREBOARD_DATE = java.util.regex.Pattern.compile("§7\\d{2}/\\d{2}/\\d{2}(?:\\d{2})?  ?§8.*");
    private static final java.util.regex.Pattern NON_ASCII = java.util.regex.Pattern.compile("[^\\x00-\\x7F§]");
    private String replacementSource, replacement;

    // Runs for every string the game draws (MixinFontRenderer and SmoothFont): keep the common
    // case (nothing to replace) free of regex work.
    public String replaceNick(String input) {
        if (input != null && mc.thePlayer != null) {
            if (this.scoreboard.getValue() && input.startsWith("§7") && SCOREBOARD_DATE.matcher(input).matches()) {
                input = NON_ASCII.matcher(input.replace("§8", "§8§k")).replaceAll("?");
            }
            String name = mc.thePlayer.getName();
            if (name == null || name.isEmpty() || !input.contains(name)) return input;
            String protect = this.protectName.getValue();
            if (!protect.equals(this.replacementSource)) {
                this.replacementSource = protect;
                this.replacement = ChatColors.formatColor(protect);
            }
            return input.replace(name, this.replacement);
        } else {
            return input;
        }
    }
}
