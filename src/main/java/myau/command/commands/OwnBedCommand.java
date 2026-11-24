package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.module.modules.BedNuker;
import myau.util.ChatUtil;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** 手动标记 / 清除 / 查看 BedNuker 的自家床保护名单。 */
public class OwnBedCommand extends Command {
    public OwnBedCommand() {
        super(new ArrayList<>(Arrays.asList("ownbed", "mybed")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker == null) {
            return;
        }
        String sub = args.size() >= 2 ? args.get(1).toLowerCase(Locale.ROOT) : "mark";
        switch (sub) {
            case "mark":
            case "set":
                BlockPos pos = bedNuker.markNearestOwnBed();
                if (pos == null) {
                    ChatUtil.sendFormatted(Myau.clientName + "&c6 格内没有找到床，请站到自家床旁边再输入&r");
                } else {
                    ChatUtil.sendFormatted(String.format("%s&a已把 &f(%d, %d, %d)&a 标记为自家床，拆床不会碰它&r",
                            Myau.clientName, pos.getX(), pos.getY(), pos.getZ()));
                }
                return;
            case "clear":
                bedNuker.clearOwnBed();
                ChatUtil.sendFormatted(Myau.clientName + "&e已清空自家床名单&r");
                return;
            case "list":
            case "info":
                String desc = bedNuker.describeOwnBeds();
                ChatUtil.sendFormatted(Myau.clientName + (desc.isEmpty() ? "&e还未识别到自家床&r" : "&a自家床：&f" + desc + "&r"));
                return;
            default:
                ChatUtil.sendFormatted(String.format("%sUsage: .%s <&omark&r/&oclear&r/&olist&r>&r",
                        Myau.clientName, args.get(0).toLowerCase(Locale.ROOT)));
        }
    }
}
