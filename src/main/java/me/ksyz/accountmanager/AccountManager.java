package me.ksyz.accountmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.ksyz.accountmanager.auth.Account;
import me.ksyz.accountmanager.utils.Nan0EventRegister;
import me.ksyz.accountmanager.utils.TokenCrypto;
import myau.util.FileUtil;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/*
 * This file is derived from https://github.com/ksyzov/AccountManager.
 * Originally licensed under the GNU LGPL.
 *
 * This modified version is licensed under the GNU GPL v3.
 */
public class AccountManager {
    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/Accounts");
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File file = new File(mc.mcDataDir, "openmyau.accounts.json");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Thread-safe: the login flows append to this list from their executor threads.
     */
    public static final List<Account> accounts = new CopyOnWriteArrayList<>();

    public static void init() {
        Nan0EventRegister.register(MinecraftForge.EVENT_BUS, new Events());
        if (!file.exists()) {
            save();
        }
    }

    /**
     * Reloads the account list from disk. A file that cannot be parsed is copied to a
     * ".corrupt-<timestamp>" sibling and the in-memory list is left untouched, so a
     * bad write never silently wipes the user's accounts.
     */
    public static synchronized void load() {
        if (!file.exists()) {
            accounts.clear();
            return;
        }
        List<Account> parsed = new ArrayList<>();
        boolean needsMigration = false;
        try {
            JsonElement json = new JsonParser().parse(FileUtil.readString(file));
            if (json != null && json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = element.getAsJsonObject();
                    String storedRefresh = string(obj, "refreshToken");
                    String storedAccess = string(obj, "accessToken");
                    if ((!storedRefresh.isEmpty() && !TokenCrypto.isProtected(storedRefresh))
                            || (!storedAccess.isEmpty() && !TokenCrypto.isProtected(storedAccess))) {
                        needsMigration = true;
                    }
                    String refresh = TokenCrypto.unprotect(storedRefresh);
                    String access = TokenCrypto.unprotect(storedAccess);
                    String username = string(obj, "username");
                    if (refresh == null || access == null) {
                        LOGGER.warn("Stored tokens for {} could not be decrypted on this machine; please log in again.", username);
                    }
                    JsonElement offline = obj.get("offline");
                    Account account = new Account(
                            refresh == null ? "" : refresh,
                            access == null ? "" : access,
                            username,
                            number(obj, "unban"),
                            string(obj, "clientId"),
                            string(obj, "scope"),
                            offline != null && offline.isJsonPrimitive() && offline.getAsBoolean()
                    );
                    account.setUuid(string(obj, "uuid"));
                    account.setLastUsed(number(obj, "lastUsed"));
                    parsed.add(account);
                }
            }
        } catch (Exception e) {
            File backup = FileUtil.quarantine(file);
            LOGGER.error("openmyau.accounts.json could not be read. A copy was kept at {}", backup, e);
            return;
        }
        accounts.clear();
        accounts.addAll(parsed);
        if (needsMigration) {
            save();
            LOGGER.info("Migrated account tokens to protected storage.");
        }
    }

    public static synchronized void save() {
        try {
            writeAccounts(accounts);
        } catch (IOException e) {
            LOGGER.error("Couldn't save openmyau.accounts.json", e);
        }
    }

    public static synchronized Account addRandomOfflineAccount(String currentUsername) throws IOException {
        String username;
        do {
            username = "Player_" + UUID.randomUUID().toString().substring(0, 8);
        } while (username.equalsIgnoreCase(currentUsername) || containsUsername(username));

        Account account = Account.offline(username);
        List<Account> updated = new ArrayList<>(accounts);
        updated.add(0, account);
        writeAccounts(updated);
        accounts.add(0, account);
        return account;
    }

    private static boolean containsUsername(String username) {
        for (Account account : accounts) {
            if (username.equalsIgnoreCase(account.getUsername())) return true;
        }
        return false;
    }

    private static void writeAccounts(List<Account> entries) throws IOException {
        JsonArray jsonArray = new JsonArray();
        for (Account account : entries) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("refreshToken", TokenCrypto.protect(account.getRefreshToken()));
            jsonObject.addProperty("accessToken", TokenCrypto.protect(account.getAccessToken()));
            jsonObject.addProperty("username", account.getUsername());
            jsonObject.addProperty("unban", account.getUnban());
            jsonObject.addProperty("clientId", account.getClientId());
            jsonObject.addProperty("scope", account.getScope());
            if (account.isOffline()) jsonObject.addProperty("offline", true);
            if (!account.getUuid().isEmpty()) jsonObject.addProperty("uuid", account.getUuid());
            if (account.getLastUsed() > 0) jsonObject.addProperty("lastUsed", account.getLastUsed());
            jsonArray.add(jsonObject);
        }
        FileUtil.writeAtomic(file, gson.toJson(jsonArray));
    }

    private static String string(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static long number(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
