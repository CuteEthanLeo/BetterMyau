package myau.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import myau.Myau;
import myau.module.Module;
import myau.property.Property;
import myau.util.ChatUtil;
import myau.util.FileUtil;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class Config {
    public static final File CONFIG_DIR = new File("./config/Myau/");
    public static Minecraft mc = Minecraft.getMinecraft();
    public static Gson gson = new GsonBuilder().setPrettyPrinting().create();
    public static String lastConfig = "default";

    public final String name;
    public final File file;

    public Config(String name, boolean newConfig) {
        this.name = sanitize(name);
        lastConfig = this.name;
        this.file = new File(CONFIG_DIR, String.format("%s.json", this.name));
        if (!CONFIG_DIR.exists() && !CONFIG_DIR.mkdirs()) {
            Myau.LOGGER.error("Could not create config directory {}", CONFIG_DIR);
        }
        if (newConfig) {
            Myau.LOGGER.info("Created: {}", this.file.getName());
        }
    }

    /**
     * Config names come from chat commands (and clickable chat components that servers can
     * send), so they are reduced to a safe file-name alphabet and never leave the config folder.
     */
    public static String sanitize(String name) {
        if (name == null) {
            return "default";
        }
        // Letters and digits of any script (Chinese names too), '_' and '-'. No dots, slashes or
        // spaces, so a name can never leave the folder or hide its extension.
        String cleaned = name.trim().replaceAll("[^\\p{L}\\p{N}_\\-]", "");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase("default")) {
            return "default";
        }
        // Device names Windows will not use as file names, whatever the extension.
        if (cleaned.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])")) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    public void load() {
        try {
            if (!file.exists()) {
                ChatUtil.sendFormatted(String.format("%sConfig file not found (&c&o%s&r). Creating default config...&r", Myau.clientName, file.getName()));
                save();
                return;
            }
            JsonObject jsonObject = parseFile();
            if (jsonObject == null) {
                ChatUtil.sendFormatted(String.format("%sInvalid config format (&c&o%s&r)&r", Myau.clientName, file.getName()));
                return;
            }
            applyModules(jsonObject, false);
            ChatUtil.sendFormatted(String.format("%sConfig has been loaded (&a&o%s&r)&r", Myau.clientName, file.getName()));
        } catch (JsonSyntaxException e) {
            ChatUtil.sendFormatted(String.format("%sConfig has invalid JSON syntax (&c&o%s&r)&r", Myau.clientName, file.getName()));
            Myau.LOGGER.error("JSON syntax error in {}", file.getName(), e);
        } catch (Exception e) {
            Myau.LOGGER.error("Error loading config {}", file.getName(), e);
            ChatUtil.sendFormatted(String.format("%sConfig couldn't be loaded (&c&o%s&r)&r", Myau.clientName, file.getName()));
        }
    }

    /**
     * Startup variant: module enabled flags are applied through
     * {@link Module#setEnabledDeferred(boolean)} so onEnabled() only runs once a world exists.
     */
    public void loadDeferred() {
        try {
            if (!file.exists()) {
                save();
                return;
            }
            JsonObject jsonObject = parseFile();
            if (jsonObject == null) {
                return;
            }
            applyModules(jsonObject, true);
        } catch (Exception e) {
            Myau.LOGGER.error("Error loading config (deferred) {}", file.getName(), e);
        }
    }

    private JsonObject parseFile() throws IOException {
        JsonElement parsed = new JsonParser().parse(FileUtil.readString(file));
        if (parsed == null || !parsed.isJsonObject()) {
            return null;
        }
        return parsed.getAsJsonObject();
    }

    private void applyModules(JsonObject jsonObject, boolean deferred) {
        for (Module module : Myau.moduleManager.modules.values()) {
            JsonElement moduleObj = jsonObject.get(module.getName());
            if (moduleObj == null || !moduleObj.isJsonObject()) {
                continue;
            }
            JsonObject object = moduleObj.getAsJsonObject();

            ArrayList<Property<?>> list = Myau.propertyManager.properties.get(module.getClass());
            if (list != null) {
                for (Property<?> property : list) {
                    if (!object.has(property.getName())) {
                        continue;
                    }
                    try {
                        if (!property.read(object)) {
                            Myau.LOGGER.warn("Rejected out-of-range value for {}.{}", module.getName(), property.getName());
                        }
                    } catch (Exception e) {
                        Myau.LOGGER.warn("Failed to load property {} for module {}", property.getName(), module.getName());
                    }
                }
            }

            JsonElement toggled = object.get("toggled");
            if (toggled != null && toggled.isJsonPrimitive()) {
                if (deferred) {
                    module.setEnabledDeferred(toggled.getAsBoolean());
                } else {
                    module.setEnabled(toggled.getAsBoolean());
                }
            }
            JsonElement key = object.get("key");
            if (key != null && key.isJsonPrimitive()) {
                // 正常保存的是 LWJGL 键码；外来 / 手改的配置可能写成键名（如 "X"）。解析不了就保持原键位，
                // 不能抛出——一抛就会中断整个循环，后面的模块全部读不到，再被自动保存覆盖成默认值。
                int code;
                try {
                    code = key.getAsInt();
                } catch (NumberFormatException e) {
                    code = org.lwjgl.input.Keyboard.getKeyIndex(key.getAsString().trim().toUpperCase(java.util.Locale.ROOT));
                    if (code == org.lwjgl.input.Keyboard.KEY_NONE) {
                        Myau.LOGGER.warn("Unknown key '{}' for module {}", key.getAsString(), module.getName());
                        code = module.getKey();
                    }
                }
                module.setKey(code);
            }
            JsonElement hidden = object.get("hidden");
            if (hidden != null && hidden.isJsonPrimitive()) {
                module.setHidden(hidden.getAsBoolean());
            }
        }
    }

    public void save() {
        save(false);
    }

    /** @param quiet no chat message on success (auto-save) */
    public void save(boolean quiet) {
        try {
            JsonObject object = new JsonObject();
            for (Module module : Myau.moduleManager.modules.values()) {
                JsonObject moduleObject = new JsonObject();
                moduleObject.addProperty("toggled", module.isEnabled());
                moduleObject.addProperty("key", module.getKey());
                moduleObject.addProperty("hidden", module.isHidden());

                ArrayList<Property<?>> list = Myau.propertyManager.properties.get(module.getClass());
                if (list != null) {
                    for (Property<?> property : list) {
                        try {
                            property.write(moduleObject);
                        } catch (Exception e) {
                            Myau.LOGGER.warn("Failed to save property {} for module {}", property.getName(), module.getName());
                        }
                    }
                }
                object.add(module.getName(), moduleObject);
            }
            FileUtil.writeAtomic(file, gson.toJson(object));
            if (!quiet) ChatUtil.sendFormatted(String.format("%sConfig has been saved (&a&o%s&r)&r", Myau.clientName, file.getName()));
        } catch (IOException e) {
            Myau.LOGGER.error("Error saving config {}", file.getName(), e);
            ChatUtil.sendFormatted(String.format("%sConfig couldn't be saved (&c&o%s&r)&r", Myau.clientName, file.getName()));
        }
    }
}
