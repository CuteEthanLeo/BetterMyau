package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.util.ChatUtil;
import myau.via.ViaHooks;
import myau.via.ViaSettings;
import myau.via.ViaUi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * .via              the global version setting and the current connection's version
 * .via auto|native  join automatically / always as plain 1.8
 * .via <version>    join every server (without its own setting) as that version, e.g. .via 1.21.4
 * .via list         the versions on offer
 */
public class ViaCommand extends Command {
    public ViaCommand() {
        super(new ArrayList<>(Arrays.asList("via", "version", "protocol")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        String prefix = Myau.clientName;
        if (args.size() < 2) {
            String status = ViaUi.status();
            ChatUtil.sendFormatted(prefix + "跨版本：全局 &b" + ViaUi.globalLabel() + "&r"
                    + (status != null ? "  &e(" + status + ")&r" : ""));
            String line = ViaHooks.debugLine();
            ChatUtil.sendFormatted(prefix + "当前连接：" + (line == null ? "&71.8 原生&r" : "&a" + line.replace("BetterMyau Via: ", "") + "&r"));
            ChatUtil.sendFormatted(prefix + "&7用法：.via auto | native | <版本> | list&r");
            return;
        }
        String arg = args.get(1).trim();
        if (arg.equalsIgnoreCase("list")) {
            int total = ViaUi.versionCount();
            if (total == 0) {
                ChatUtil.sendFormatted(prefix + "&e" + (ViaUi.status() != null ? ViaUi.status() : "暂无可选版本") + "&r");
                return;
            }
            for (Map.Entry<String, List<ViaUi.Choice>> group : ViaUi.versionGroups().entrySet()) {
                StringBuilder names = new StringBuilder();
                for (ViaUi.Choice choice : group.getValue()) {
                    if (names.length() > 0) names.append("&8, &7");
                    names.append(choice.label);
                }
                ChatUtil.sendFormatted(prefix + "&b" + group.getKey() + "&r &7" + names + "&r");
            }
            return;
        }
        String choice;
        if (arg.equalsIgnoreCase(ViaSettings.AUTO)) {
            choice = ViaSettings.AUTO;
        } else if (arg.equalsIgnoreCase(ViaSettings.NATIVE) || arg.equalsIgnoreCase("1.8") || arg.equalsIgnoreCase("1.8.9")) {
            choice = ViaSettings.NATIVE;
        } else {
            choice = ViaUi.resolve(arg);
            if (choice == null) {
                ChatUtil.sendFormatted(prefix + "&c没有这个版本：" + arg + "&r &7（.via list 查看全部）&r");
                return;
            }
        }
        ViaUi.setGlobal(choice);
        ChatUtil.sendFormatted(prefix + "跨版本：全局改为 &b" + ViaUi.label(choice) + "&r，下次连接生效");
    }
}
