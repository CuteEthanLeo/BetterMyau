package myau.management;

import myau.Myau;
import myau.util.FileUtil;
import net.minecraft.client.Minecraft;

import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.stream.Collectors;

public abstract class PlayerFileManager {
    public static Minecraft mc = Minecraft.getMinecraft();
    public ArrayList<String> players;
    public File file;
    public Color color;

    public PlayerFileManager(File file, Color color) {
        this.players = new ArrayList<>();
        this.file = file;
        this.color = color;
    }

    public void load() {
        if (!file.exists()) {
            try {
                if ((file.getParentFile().exists() || file.getParentFile().mkdirs()) && file.createNewFile()) {
                    Myau.LOGGER.info("File created: {}", file.getName());
                }
            } catch (IOException e) {
                Myau.LOGGER.error("Error creating {}", file.getName(), e);
            }
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            players.clear();
            players.addAll(reader.lines().map(String::trim).collect(Collectors.toList()));
        } catch (IOException e) {
            Myau.LOGGER.error("Error reading {}", file.getName(), e);
        }
    }

    public void save() {
        try {
            FileUtil.writeAtomic(file, String.join("\n", players));
        } catch (IOException e) {
            Myau.LOGGER.error("Error saving {}", file.getName(), e);
        }
    }

    public String add(String name) {
        if (isFriend(name)) {
            return null;
        }
        players.add(name);
        save();
        return name;
    }

    public String remove(String name) {
        for (String player : players) {
            if (player.equalsIgnoreCase(name)) {
                players.remove(player);
                save();
                return player;
            }
        }
        return null;
    }

    public void clear() {
        players.clear();
        save();
    }

    public boolean isFriend(String string) {
        // A plain loop: this runs for every player on screen, and a stream allocates each time.
        ArrayList<String> list = this.players;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(string)) return true;
        }
        return false;
    }

    public ArrayList<String> getPlayers() {
        return this.players;
    }

    public Color getColor() {
        return this.color;
    }
}
