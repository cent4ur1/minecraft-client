package keystrokesmod.script;

import keystrokesmod.Raven;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public class SecureClassLoader extends URLClassLoader {
    private static final List<String> WHITELISTED_PACKAGES = Arrays.asList("sun.reflect", "keystrokesmod", "java.lang", "java.util", "java.awt");

    private static final Set<String> BLOCKED_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "java.lang.Runtime",
            "java.lang.Process",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessHandle",
            "java.lang.System",
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.SecurityManager",
            "java.lang.Compiler",
            "java.lang.Package",
            "java.lang.Module",
            "java.awt.Desktop",
            "java.awt.Robot",
            "java.awt.Toolkit"
    )));

    public SecureClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isClassSafe(name)) {
            throw new ClassNotFoundException("Unsafe class detected: " + name);
        }
        return super.loadClass(name, resolve);
    }

    private boolean isClassSafe(String name) {
        boolean hasAllowedSuffix = name.endsWith("Exception") || name.endsWith("Throwable");

        boolean isAllowedImport = Raven.scriptManager.imports.stream().anyMatch(prefix -> name.toLowerCase().startsWith(prefix));
        boolean isScriptClass = name.startsWith("sc_") && !name.contains(".");

        boolean isWhitelistedPackage = WHITELISTED_PACKAGES.stream().anyMatch(name::startsWith);

        if (matchesClassOrNested(name, BLOCKED_CLASSES)) {
            return false;
        }

        return hasAllowedSuffix || isAllowedImport || isScriptClass || isWhitelistedPackage;
    }


    private boolean matchesClassOrNested(String name, Set<String> classes) {
        for (String allowed : classes) {
            if (name.equals(allowed) || name.startsWith(allowed + "$")) {
                return true;
            }
        }

        return false;
    }
}