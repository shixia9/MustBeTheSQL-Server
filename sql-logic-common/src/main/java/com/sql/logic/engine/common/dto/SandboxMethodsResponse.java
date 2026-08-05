package com.sql.logic.engine.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Capabilities response for {@code GET /api/v1/sandbox/methods}.
 *
 * <p>Surfaces the supported languages, the selected runtime, VNC availability,
 * and other capability flags so the frontend can adapt its UI (e.g. show/hide
 * the VNC button, display available runtimes).
 */
@Data
@AllArgsConstructor
public class SandboxMethodsResponse {
    /** Supported language keys (e.g. ["python", "python-vnc", "javascript", ...]). */
    private List<String> supportedLanguages;

    /** The selected runtime id ("docker" / "podman" / "nerdctl" / "local" / "none"). */
    private String selectedRuntime;

    /** Human-readable reason for the runtime selection. */
    private String selectionReason;

    /** Whether VNC/GUI sessions are supported (requires vnc-enabled=true + image). */
    private boolean vncSupported;

    /** Whether file transfer (get-file / docker cp) is supported. */
    private boolean fileTransferSupported;

    /**
     * Additional capability flags (e.g. {"dependencyInstall": true, "streamingOutput": true}).
     */
    private Map<String, Object> capabilities;
}
