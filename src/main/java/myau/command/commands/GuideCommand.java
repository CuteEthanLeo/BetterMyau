package myau.command.commands;

import myau.command.Command;
import myau.guide.GuideScreen;
import myau.ui.glass.Defer;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;

/** {@code .guide [topic]}: opens the in-client guide, at a topic when one is named. */
public class GuideCommand extends Command {
    public GuideCommand() {
        super(new ArrayList<>(Arrays.asList("guide", "tutorial")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        String topic = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : null;
        // Next frame: the chat closes itself (and whatever just opened) right after this returns.
        Defer.nextFrame(() -> Minecraft.getMinecraft().displayGuiScreen(new GuideScreen(null, topic)));
    }
}
