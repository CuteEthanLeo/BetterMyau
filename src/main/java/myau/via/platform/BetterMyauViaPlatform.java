package myau.via.platform;

import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;

import java.io.File;
import java.util.logging.Logger;

/**
 * The ViaVersion platform for this client: one translated connection per server connection, all
 * client side. Configs live in the folder given (config/Myau/via). Custom payloads and connection
 * details are never sent, so the server is not told that translation happens here.
 */
public final class BetterMyauViaPlatform extends UserConnectionViaVersionPlatform {
    private static volatile String version = "dev";

    public BetterMyauViaPlatform(File folder, String clientVersion) {
        super(folder);
        if (clientVersion != null) version = clientVersion;
    }

    @Override
    public Logger createLogger(String name) {
        return new JulToLog4j(name);
    }

    @Override
    public String getPlatformName() {
        return "BetterMyau";
    }

    @Override
    public String getPlatformVersion() {
        return version;
    }
}
