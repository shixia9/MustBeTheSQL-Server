package com.sql.logic.engine.domain.agentic.core.bus;

/**
 * Operating mode for the message-bus ↔ AgentOrchestrator integration.
 *
 * <p>The mode is selected via the {@code bus-orc.mode} property and governs how
 * {@code ManagerAgent} dispatches work to its team of Agents:
 *
 * <ul>
 *   <li>{@link #OFF} — pure direct-call dispatch. The bus is
 *       not involved in orchestration. This is the fail-safe default so that
 *       REQ-02 ships with zero behavioural change until an operator opts in.</li>
 *   <li>{@link #BYPASS} — dual-write: the existing direct {@code generateReply}
 *       call still drives execution (unchanged behaviour), and each dispatch is
 *       <em>mirrored</em> onto the bus as a {@link BusMessage.TaskDispatch} /
 *       {@link BusMessage.ToolResult} pair for observability and equivalence
 *       verification. This is the M9a P0 milestone deliverable.</li>
 *   <li>{@link #SWITCH} — the bus becomes the channel of record for business
 *       messages: {@code ManagerAgent} sends a {@code TaskDispatch}, the worker
 *       endpoint receives it, executes {@code generateReply}, and replies with a
 *       {@code ToolResult} matched by {@code correlationId}. {@code NEXT_NODE}
 *       stays in graph state for edge routing. This is M9b.</li>
 * </ul>
 */
public enum BusOrchestrationMode {

    /** Legacy direct-call dispatch; bus unused. Default. */
    OFF,

    /** Dual-write: direct call + bus mirror for parity verification. */
    BYPASS,

    /** Business messages travel over the bus (request/reply). */
    SWITCH
}
