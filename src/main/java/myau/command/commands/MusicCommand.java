package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.module.modules.MusicPlayer;
import myau.music.MusicService;
import myau.music.PlayMode;
import myau.music.local.LocalLibrary;
import myau.music.model.Track;
import myau.music.ui.MusicScreen;
import myau.music.ui.MusicText;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code .music} (alias {@code .mu}): search and control the music player from chat. Results are
 * clickable. The module is called MusicPlayer, so this name does not clash with the automatic
 * module command.
 */
public class MusicCommand extends Command {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private List<Track> lastResults = Collections.emptyList();
    private String lastQuery = "";

    public MusicCommand() {
        super(new ArrayList<>(Arrays.asList("music", "mu")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        MusicService m = MusicService.get();
        if (args.size() < 2) {
            this.help();
            return;
        }
        String sub = args.get(1).toLowerCase();
        String rest = String.join(" ", args.subList(Math.min(2, args.size()), args.size())).trim();
        switch (sub) {
            case "search":
            case "s":
                if (rest.isEmpty()) {
                    say("&c用法：.music search <关键词>");
                    return;
                }
                this.search(rest, false);
                return;
            case "play":
            case "p":
                if (rest.isEmpty()) {
                    m.setPaused(false);
                    return;
                }
                if (rest.matches("\\d+")) {
                    int n = Integer.parseInt(rest);
                    if (n < 1 || n > this.lastResults.size()) {
                        say("&c没有第 " + n + " 首，先用 .music search 搜索");
                        return;
                    }
                    m.playList(new ArrayList<>(this.lastResults), n - 1, "搜索：" + this.lastQuery);
                    say("&a▶ &f" + this.lastResults.get(n - 1));
                    return;
                }
                this.search(rest, true);
                return;
            case "pause":
                m.setPaused(true);
                return;
            case "toggle":
                m.togglePause();
                return;
            case "next":
            case "n":
                m.next();
                return;
            case "prev":
            case "previous":
                m.previous();
                return;
            case "stop":
                m.stop();
                return;
            case "vol":
            case "volume":
                try {
                    int v = Integer.parseInt(rest);
                    m.settings().setVolume(v);
                    say("&7音量：&f" + m.settings().volume() + "%");
                } catch (NumberFormatException e) {
                    say("&7音量：&f" + m.settings().volume() + "% &8(.music vol 0-100)");
                }
                return;
            case "seek":
                long ms = parseTime(rest);
                if (ms < 0) say("&c用法：.music seek 1:23");
                else m.seek(ms);
                return;
            case "mode":
                this.mode(m, rest);
                return;
            case "fm":
                m.startFm();
                return;
            case "daily":
                MusicService.onMain(m.dailySongs(), (tracks, err) -> {
                    if (err != null) say("&c每日推荐：" + err);
                    else if (tracks.isEmpty()) say("&7每日推荐是空的");
                    else {
                        m.playList(tracks, 0, "每日推荐");
                        say("&a▶ &f每日推荐 &7(" + tracks.size() + " 首)");
                    }
                });
                return;
            case "like":
                if (m.current() == null) say("&7现在没有在播放");
                else m.toggleLike(m.current());
                return;
            case "now":
            case "np":
                this.now(m);
                return;
            case "queue":
            case "q":
                this.queue(m);
                return;
            case "open":
                m.runNextFrame(() -> mc.displayGuiScreen(new MusicScreen()));
                return;
            case "login":
                m.runNextFrame(() -> mc.displayGuiScreen(MusicScreen.signIn()));
                return;
            case "logout":
                m.signOut();
                say("&7已退出网易云账号");
                return;
            case "local":
                this.local(m, rest);
                return;
            case "diag":
                say("&7正在检测网易云接口…");
                MusicService.onMain(m.diagnose(), (lines, err) -> {
                    if (err != null) say("&c检测失败：" + err);
                    else for (String l : lines) say(l);
                });
                return;
            default:
                this.help();
        }
    }

    private void search(String query, boolean playFirst) {
        MusicService m = MusicService.get();
        say("&7正在搜索「&f" + query + "&7」…");
        MusicService.onMain(m.search(query, 0, 10), (r, err) -> {
            if (err != null) {
                say("&c搜索失败：" + err);
                return;
            }
            this.lastResults = new ArrayList<>(r.tracks);
            this.lastQuery = query;
            if (r.tracks.isEmpty()) {
                say("&7没有找到「" + query + "」");
                return;
            }
            if (playFirst) {
                m.playList(this.lastResults, 0, "搜索：" + query);
                say("&a▶ &f" + r.tracks.get(0));
                return;
            }
            say("&7搜索「&f" + query + "&7」，点击歌名播放：");
            for (int i = 0; i < Math.min(8, r.tracks.size()); i++) {
                Track t = r.tracks.get(i);
                String line = String.format(" §8%d. §f%s §7- %s §8%s%s", i + 1, t.name, t.artistText(),
                        MusicText.time(t.durationMs), t.vip() ? " §6VIP" : "");
                IChatComponent c = new ChatComponentText(line);
                c.setChatStyle(new ChatStyle()
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, ".music play " + (i + 1)))
                        .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                new ChatComponentText(EnumChatFormatting.GRAY + "点击播放 " + t.name))));
                ChatUtil.send(c);
            }
        });
    }

    private void mode(MusicService m, String arg) {
        PlayMode mode;
        switch (arg.toLowerCase()) {
            case "seq":
            case "sequence":
                mode = PlayMode.SEQUENCE;
                break;
            case "loop":
                mode = PlayMode.LOOP;
                break;
            case "one":
            case "single":
                mode = PlayMode.ONE;
                break;
            case "shuffle":
            case "random":
                mode = PlayMode.SHUFFLE;
                break;
            default:
                mode = m.mode().next();
                break;
        }
        m.setMode(mode);
        say("&7播放模式：&f" + mode.label);
    }

    private void now(MusicService m) {
        Track t = m.current();
        if (t == null) {
            say("&7现在没有在播放");
            return;
        }
        say("&7" + (m.isPaused() ? "已暂停" : "正在播放") + "：&f" + t + " &8" + MusicText.time(m.positionMs()) + " / " + MusicText.time(m.durationMs()));
    }

    private void queue(MusicService m) {
        List<Track> q = m.queue();
        if (q.isEmpty()) {
            say("&7播放队列是空的");
            return;
        }
        int at = Math.max(0, m.queueIndex());
        say("&7播放队列 &8(" + q.size() + " 首，" + m.mode().label + ")");
        for (int i = Math.max(0, at - 2); i < Math.min(q.size(), at + 6); i++) {
            say((i == at ? " &a▶ &f" : " &8" + (i + 1) + ". &7") + q.get(i));
        }
    }

    private void local(MusicService m, String path) {
        MusicPlayer player = (MusicPlayer) Myau.moduleManager.modules.get(MusicPlayer.class);
        if (!path.isEmpty()) {
            File f = new File(path.replace("\"", ""));
            if (f.isFile() && f.getName().toLowerCase().endsWith(".mp3")) {
                m.playList(Collections.singletonList(LocalLibrary.fromFile(f)), 0, "本地文件");
                return;
            }
            if (!f.isDirectory()) {
                say("&c找不到文件夹：" + path);
                return;
            }
            if (player != null) player.localFolder.setValue(f.getAbsolutePath());
        }
        say("&7正在扫描本地音乐…");
        m.scanLocal(() -> {
            List<Track> tracks = m.local().tracks();
            if (tracks.isEmpty()) {
                say("&7" + m.local().folder() + " 里没有 mp3");
                return;
            }
            m.playList(tracks, 0, "本地音乐");
            say("&a▶ &f本地音乐 &7(" + tracks.size() + " 首，来自 " + m.local().folder() + ")");
        });
    }

    private void help() {
        say("&f.music &7用法：");
        say(" &fsearch <词> &7搜索（结果可点击）  &fplay [序号|词] &7播放");
        say(" &fpause / toggle / next / prev / stop  &fvol <0-100>  &fseek <分:秒>");
        say(" &fmode [seq|loop|one|shuffle]  &ffm &7私人FM  &fdaily &7每日推荐  &flike &7红心");
        say(" &fnow / queue  &fopen &7打开界面  &flogin / logout  &flocal [文件夹|mp3]  &fdiag &7检测接口");
    }

    private static long parseTime(String s) {
        try {
            String[] parts = s.trim().split(":");
            long total = 0;
            for (String p : parts) total = total * 60 + Long.parseLong(p.trim());
            return total * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void say(String message) {
        ChatUtil.sendFormatted(Myau.clientName + message);
    }
}
