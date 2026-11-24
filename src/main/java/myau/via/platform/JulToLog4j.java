package myau.via.platform;

import org.apache.logging.log4j.LogManager;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** ViaVersion logs through java.util.logging; this sends it to the game's log4j log instead. */
final class JulToLog4j extends Logger {
    private static final SimpleFormatter FORMAT = new SimpleFormatter();
    private final org.apache.logging.log4j.Logger target;

    JulToLog4j(String name) {
        super("BetterMyau-" + name, null);
        this.target = LogManager.getLogger("BetterMyau/" + name);
        setLevel(Level.INFO);
    }

    @Override
    public void log(LogRecord record) {
        if (!isLoggable(record.getLevel())) return;
        String message = FORMAT.formatMessage(record);
        Throwable thrown = record.getThrown();
        int level = record.getLevel().intValue();
        if (level >= Level.SEVERE.intValue()) target.error(message, thrown);
        else if (level >= Level.WARNING.intValue()) target.warn(message, thrown);
        else if (level >= Level.INFO.intValue()) target.info(message, thrown);
        else target.debug(message, thrown);
    }
}
