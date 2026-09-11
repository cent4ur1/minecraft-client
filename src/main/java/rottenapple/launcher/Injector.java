package rottenapple.launcher;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Attaches to a running JVM and loads the RottenApple agent into it.
 *
 * <p>Uses the Attach API purely through reflection, so this compiles on any
 * JDK without extra dependencies. At runtime the launching JVM must be a JDK
 * (11+ recommended); the <b>target</b> (Lunar) can be any Java 8+ runtime.</p>
 *
 * <p>Usage: {@code java -cp rottenapple-launcher.jar rottenapple.launcher.Injector <pid> <agentJar>}</p>
 */
public class Injector {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: Injector <pid> <agentJar>");
            System.exit(1);
        }
        String pid = args[0];
        String agent = args[1];

        if (!new File(agent).isFile()) {
            System.err.println("[RottenApple] agent jar not found: " + agent);
            System.exit(1);
        }

        final Class<?> vmClass;
        try {
            vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            System.err.println("[RottenApple] Attach API not available in this JVM.");
            System.err.println("[RottenApple] Run the launcher with a JDK (not a JRE), e.g. Temurin 11/17/21.");
            System.exit(2);
            return;
        }

        try {
            // Optional sanity check: is the pid attachable?
            try {
                Method list = vmClass.getMethod("list");
                Object raw = list.invoke(null);
                boolean found = false;
                if (raw instanceof List) {
                    for (Object d : (List<?>) raw) {
                        if (d != null && String.valueOf(d).contains(pid)) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    System.err.println("[RottenApple] warning: pid " + pid
                            + " is not in the attach list; trying anyway.");
                }
            } catch (Throwable ignored) {
                // listing is best-effort only
            }

            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            try {
                vmClass.getMethod("loadAgent", String.class).invoke(vm, new File(agent).getAbsolutePath());
            } finally {
                try {
                    vmClass.getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                    // detach best-effort
                }
            }
            System.out.println("[RottenApple] injected into pid " + pid);
        } catch (Exception e) {
            Throwable cause = e.getCause();
            Throwable shown = cause != null ? cause : e;
            String chain = String.valueOf(e) + " <- " + String.valueOf(cause);
            if (chain.contains("AttachNotSupported")) {
                System.err.println("[RottenApple] target JVM refused dynamic attach: " + shown);
                System.err.println("[RottenApple] pid=" + pid
                        + " user=" + System.getProperty("user.name")
                        + " tmpdir=" + System.getProperty("java.io.tmpdir")
                        + " java=" + System.getProperty("java.version"));
                System.err.println("[RottenApple] likely causes, in order:");
                System.err.println("  1. pid is not the game JVM (e.g. the Lunar launcher app).");
                System.err.println("  2. Lunar runs the game with attach disabled (-XX:+DisableAttachMechanism).");
                System.err.println("  3. Target runs as a different OS user.");
                System.err.println("[RottenApple] If (2), use the -javaagent fallback printed by launch.sh.");
            } else {
                System.err.println("[RottenApple] injection failed: " + shown);
                System.err.println("[RottenApple] tips: same OS user as Lunar; "
                        + "any stage is fine (menu, singleplayer, server).");
                System.exit(4);
            }
            System.exit(3);
        }
    }
}
