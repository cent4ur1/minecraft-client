package com.rottenapple.agent;

import com.rottenapple.bridge.GameBridge;
import com.rottenapple.client.gui.RottenAppleGui;
import com.rottenapple.module.ModuleManager;

import java.lang.instrument.Instrumentation;

/**
 * RottenApple java agent. Works both as {@code -javaagent} (premain) and as a
 * dynamically attached agent (agentmain, used by the launcher).
 *
 * <p>JDK-only imports. No Weave, no Mixin, no Minecraft classes.</p>
 *
 * <p>Lifecycle is recorded to {@code ~/RottenApple-status.log} so a missing
 * menu can be diagnosed without hunting game logs.</p>
 */
public final class RottenAppleAgent {

    private static volatile boolean started;
    /** Bump on every behavior change; printed at startup to identify builds. */
    public static final String BUILD = "1.5";
    private static volatile java.io.PrintWriter statusOut;

    public static void premain(String args, Instrumentation inst) {
        start(inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(inst, "agentmain");
    }

    /** Appends to console and to ~/RottenApple-status.log. */
    public static void status(String msg) {
        System.out.println("[RottenApple] " + msg);
        try {
            if (statusOut == null) {
                java.io.File f = new java.io.File(
                        System.getProperty("user.home"), "RottenApple-status.log");
                statusOut = new java.io.PrintWriter(new java.io.FileWriter(f, true), true);
                statusOut.println("===== RottenApple session: " + new java.util.Date() + " =====");
            }
            statusOut.println("[" + new java.util.Date() + "] " + msg);
        } catch (Throwable ignored) {
            // diagnostics must never break the client
        }
    }

    private static synchronized void start(Instrumentation inst, String source) {
        if (started) {
            return;
        }
        started = true;
        status("agent build " + BUILD + " loaded via " + source + ", waiting for Minecraft...");
        try {
            ModuleManager.init();
        } catch (Throwable t) {
            status("module init failed: " + t);
        }
        try {
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread th, Throwable ex) {
                    status("uncaught in " + th.getName() + ": " + ex);
                }
            });
        } catch (Throwable ignored) {
            // diagnostics must never break the client
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // Poll indefinitely: injection works at any stage (launcher,
                // menu, singleplayer, server) and the game may start later.
                while (!GameBridge.waitForGame(inst, 60000L)) {
                    // quiet retry; the launcher already told the user what to expect
                }
                status("found game class: " + GameBridge.getFoundClassName());
                try {
                    status("requesting menu show (toggle with P or INSERT)");
                    RottenAppleGui.showGui();
                } catch (Throwable t) {
                    status("menu show FAILED: " + t);
                }
                pollLoop();
            }
        }, "RottenApple-Bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private static void pollLoop() {
        boolean wasDown = false;
        long lastToggle = 0;
        while (true) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            try {
                GameBridge.refresh();
                boolean down = GameBridge.isToggleDown();
                long now = System.currentTimeMillis();
                if (down && !wasDown && now - lastToggle > 250) {
                    lastToggle = now;
                    status("toggle key pressed");
                    if (!GameBridge.isGuiOpen()) {
                        RottenAppleGui.toggle();
                    }
                }
                wasDown = down;
                GameBridge.applyEffects();
                try {
                    ModuleManager.handleKeybinds();
                } catch (Throwable ignored) {
                }
                try {
                    ModuleManager.tickAll();
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
                // polling must never die
            }
        }
    }
}
