package myau.command.commands;

import myau.Myau;
import myau.command.Command;
import myau.config.Config;
import myau.ml.MLAim;
import myau.module.modules.KillAura;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code .ml}: training and choosing models for KillAura's ML rotation mode. Ported from Zak0.
 *
 * <p>Training runs the bundled Python script (needs Python 3 with numpy and torch) on the CSV files
 * DataCollector wrote; the model it exports is picked up straight away.
 */
public class MLCommand extends Command {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String RESOURCE_PATH = "/assets/minecraft/bettermyau/ai/train_aim_model.py";
    private static final String SCRIPT_NAME = "train_aim_model.py";
    private static volatile boolean busy = false;

    public MLCommand() {
        super(new ArrayList<>(Arrays.asList("ml")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(String.format("%sUsage: .%s train | export | folder | reload | pycheck | list | use <&omodel&r|latest>", Myau.clientName, args.get(0)));
            return;
        }
        switch (args.get(1).toLowerCase(Locale.ROOT)) {
            case "train":
                this.train();
                return;
            case "export":
            case "script":
                this.exportScript();
                return;
            case "folder":
            case "dir":
                this.openFolder();
                return;
            case "reload":
                this.reloadModel();
                return;
            case "pycheck":
            case "check":
                this.checkPython();
                return;
            case "list":
            case "models":
                this.listModels();
                return;
            case "use":
            case "model":
                if (args.size() < 3) {
                    ChatUtil.sendFormatted(String.format("%sUsage: .%s use <&omodel&r|latest>", Myau.clientName, args.get(0)));
                } else {
                    this.useModel(String.join(" ", args.subList(2, args.size())));
                }
                return;
            default:
                ChatUtil.sendFormatted(String.format("%sInvalid argument: &o%s&r", Myau.clientName, args.get(1)));
        }
    }

    // ---------------------------------------------------------------- sub-commands

    private void exportScript() {
        File scriptFile = getScriptFile();
        if (this.writeScript(scriptFile)) {
            ChatUtil.sendFormatted(String.format("%sTraining script exported: &o%s&r", Myau.clientName, scriptFile.getAbsolutePath()));
        }
    }

    private void train() {
        if (busy) {
            ChatUtil.sendFormatted(String.format("%sTraining is already running", Myau.clientName));
            return;
        }
        File scriptFile = getScriptFile();
        if (!this.writeScript(scriptFile)) return;
        File[] csvFiles = MLAim.DATA_DIR.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            ChatUtil.sendFormatted(String.format("%sNo training data in &o%s&r (record some with DataCollector first)", Myau.clientName, MLAim.DATA_DIR.getAbsolutePath()));
            return;
        }
        ChatUtil.sendFormatted(String.format("%sTraining ML model (%d CSV files)...", Myau.clientName, csvFiles.length));
        busy = true;
        Thread thread = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>(Arrays.asList(findPython()));
                cmd.add(scriptFile.getAbsolutePath());
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(scriptFile.getParentFile());
                pb.environment().put("PYTHONUNBUFFERED", "1");
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                pb.environment().put("MYAU_NO_PAUSE", "1");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                List<String> tail = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        tail.add(line);
                        if (tail.size() > 10) tail.remove(0);
                        if (shouldEcho(line)) say(line);
                    }
                }
                int code = process.waitFor();
                if (code == 0) {
                    say("ML model trained.");
                    mc.addScheduledTask(this::reloadModel);
                } else {
                    say(String.format("Training failed (%d): %s", code, String.join(" | ", tail)));
                }
            } catch (Exception e) {
                say("Training failed: " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "BetterMyau-ML-Train");
        thread.setDaemon(true);
        thread.start();
    }

    private void checkPython() {
        ChatUtil.sendFormatted(String.format("%sChecking Python environment...", Myau.clientName));
        Thread thread = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>(Arrays.asList(findPython()));
                cmd.add("-c");
                cmd.add("import sys, numpy, torch; print(sys.executable); print('numpy=' + numpy.__version__); print('torch=' + torch.__version__)");
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                        if (lines.size() > 12) lines.remove(0);
                    }
                }
                int code = process.waitFor();
                if (code == 0) {
                    say("Python ML OK: " + String.join(" | ", lines));
                } else {
                    say(String.format("Python ML check failed (%d): %s", code, String.join(" | ", lines)));
                }
            } catch (Exception e) {
                say("Python not found: " + e.getMessage());
            }
        }, "BetterMyau-ML-PyCheck");
        thread.setDaemon(true);
        thread.start();
    }

    private void openFolder() {
        File dir = MLAim.MODEL_DIR.getAbsoluteFile();
        if (!dir.exists()) dir.mkdirs();
        // off the client thread: the desktop call can stall for a moment
        Thread thread = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(dir);
            } catch (Throwable e) {
                try {
                    org.lwjgl.Sys.openURL(dir.toURI().toString());
                } catch (Throwable ignored) {
                    say("Could not open " + dir.getPath());
                }
            }
        }, "BetterMyau-open-folder");
        thread.setDaemon(true);
        thread.start();
        ChatUtil.sendFormatted(String.format("%sOpened: &o%s&r", Myau.clientName, dir.getAbsolutePath()));
    }

    private void listModels() {
        List<String> models = MLAim.listModelFiles();
        KillAura aura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        File loaded = aura != null ? aura.getMlAim().getLoadedFile() : null;
        if (models.isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sNo models in &o%s&r", Myau.clientName, MLAim.MODEL_DIR.getAbsolutePath()));
            return;
        }
        ChatUtil.sendFormatted(String.format("%sModels (%d):", Myau.clientName, models.size()));
        for (String name : models) {
            boolean active = loaded != null && loaded.getName().equals(name);
            ChatUtil.sendFormatted((active ? "&a● " : "&7- ") + name + (active ? " &7(loaded)" : ""));
        }
    }

    private void useModel(String name) {
        KillAura aura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (aura == null) return;
        if (!name.equalsIgnoreCase(MLAim.LATEST) && MLAim.resolve(name) == null) {
            ChatUtil.sendFormatted(String.format("%sModel not found: &o%s&r (see .ml list)", Myau.clientName, name));
            return;
        }
        aura.mlModel.setValue(name.equalsIgnoreCase(MLAim.LATEST) ? MLAim.LATEST : name);
        this.reloadModel();
    }

    private void reloadModel() {
        KillAura aura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
        if (aura == null) return;
        String problem = aura.getMlAim().load(aura.mlModel.getValue());
        if (problem == null) {
            ChatUtil.sendFormatted(String.format("%sML model loaded: &o%s&r", Myau.clientName, aura.getMlAim().getLoadedFile().getName()));
        } else {
            ChatUtil.sendFormatted(String.format("%sML model not loaded: %s", Myau.clientName, problem));
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean writeScript(File scriptFile) {
        try (InputStream input = MLCommand.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                ChatUtil.sendFormatted(String.format("%sScript missing from the jar: %s", Myau.clientName, RESOURCE_PATH));
                return false;
            }
            File dir = scriptFile.getParentFile();
            if (!dir.exists()) dir.mkdirs();
            try (OutputStream output = new FileOutputStream(scriptFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            ChatUtil.sendFormatted(String.format("%sCould not export the script: %s", Myau.clientName, e.getMessage()));
            return false;
        }
    }

    /** The script lives in the config folder: it looks for CollectData/ and ML/ next to itself. */
    private static File getScriptFile() {
        return new File(Config.CONFIG_DIR, SCRIPT_NAME).getAbsoluteFile();
    }

    private static boolean shouldEcho(String line) {
        return line.startsWith("Found ") || line.startsWith("Total ") || line.startsWith("Offline ")
                || line.startsWith("No ") || line.startsWith("  ") || line.contains("exported to")
                || line.startsWith("Epoch ") && isEchoedEpoch(line);
    }

    /** 200 epoch lines would flood the chat: show every 20th and the last. */
    private static boolean isEchoedEpoch(String line) {
        try {
            String fraction = line.substring("Epoch ".length(), line.indexOf(' ', "Epoch ".length()));
            int slash = fraction.indexOf('/');
            int epoch = Integer.parseInt(fraction.substring(0, slash));
            int total = Integer.parseInt(fraction.substring(slash + 1));
            return epoch == 1 || epoch % 20 == 0 || epoch == total;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** First Python that can import numpy and torch; plain "python" when none can (the error then says why). */
    private static String[] findPython() {
        String[][] candidates = {{"python"}, {"python3"}, {"py", "-3"}, {"py"}};
        for (String[] cmd : candidates) {
            try {
                List<String> full = new ArrayList<>(Arrays.asList(cmd));
                full.add("-c");
                full.add("import numpy, torch");
                Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
                // drain so a chatty import can't block on a full pipe
                try (InputStream in = process.getInputStream()) {
                    byte[] sink = new byte[1024];
                    while (in.read(sink) != -1) {
                        // discard
                    }
                }
                if (process.waitFor() == 0) return cmd;
            } catch (Exception ignored) {
            }
        }
        return new String[]{"python"};
    }

    /** Chat from a worker thread: hand it to the client thread. */
    private static void say(String message) {
        mc.addScheduledTask(() -> ChatUtil.sendFormatted(Myau.clientName + message));
    }
}
