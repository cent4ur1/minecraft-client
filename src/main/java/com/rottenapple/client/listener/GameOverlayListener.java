package com.rottenapple.client.listener;

/**
 * Legacy Weave overlay listener (v1). No longer used.
 *
 * <p>Replaced by {@code com.rottenapple.bridge.GameBridge}, which applies the
 * same effects through a reflection-only polling loop (no event bus, no
 * mappings dependency).</p>
 */
@Deprecated
public final class GameOverlayListener {
    private GameOverlayListener() {}
}
