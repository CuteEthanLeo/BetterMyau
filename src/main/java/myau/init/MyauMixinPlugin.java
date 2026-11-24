package myau.init;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mixin config plugin that discovers every class under the mixin package at runtime, so
 * mixins.*.json does not need a hand-maintained list.
 */
public class MyauMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("BetterMyau/Mixin");
    private static final List<MyauMixinPlugin> mixinPlugins = new ArrayList<>();
    private String mixinPackage;
    private List<String> mixins = null;

    public static List<MyauMixinPlugin> getMixinPlugins() {
        return mixinPlugins;
    }

    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
        mixinPlugins.add(this);
    }

    public URL getBaseUrlForClassUrl(URL classUrl) {
        String string = classUrl.toString();
        if (classUrl.getProtocol().equals("jar")) {
            try {
                return new URL(string.substring(4).split("!")[0]);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        } else if (string.endsWith(".class")) {
            try {
                return new URL(string.replace("\\", "/").replace(this.getClass().getCanonicalName().replace(".", "/") + ".class", ""));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        } else {
            return classUrl;
        }
    }

    public String getMixinPackage() {
        return this.mixinPackage;
    }

    public String getMixinBaseDir() {
        return this.mixinPackage.replace(".", "/");
    }

    public void tryAddMixinClass(String className) {
        String norm = (className.endsWith(".class") ? className.substring(0, className.length() - ".class".length()) : className).replace("\\", "/").replace("/", ".");
        if (norm.startsWith(this.getMixinPackage() + ".") && !norm.endsWith(".")) {
            this.mixins.add(norm.substring(this.getMixinPackage().length() + 1));
        }
    }

    public List<String> getMixins() {
        if (this.mixins != null) {
            return this.mixins;
        }
        this.mixins = new ArrayList<>();
        URL classUrl = this.getClass().getProtectionDomain().getCodeSource().getLocation();
        Path file;
        try {
            file = Paths.get(this.getBaseUrlForClassUrl(classUrl).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        LOGGER.debug("Scanning for mixins in {}", file);
        if (Files.isDirectory(file)) {
            this.walkDir(file);
        } else {
            this.walkJar(file);
        }
        LOGGER.info("Found {} mixins", this.mixins.size());
        return this.mixins;
    }

    private void walkDir(Path classRoot) {
        try (Stream<Path> classes = Files.walk(classRoot.resolve(this.getMixinBaseDir()))) {
            classes.map((it) -> classRoot.relativize(it).toString()).forEach(this::tryAddMixinClass);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void walkJar(Path file) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry next;
            while ((next = zis.getNextEntry()) != null) {
                this.tryAddMixinClass(next.getName());
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    public String getRefMapperConfig() {
        return null;
    }

    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }
}
