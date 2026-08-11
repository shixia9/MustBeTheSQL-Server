package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * SWITCH-mode bootstrap: registers a {@link BusWorkerEndpoint} for each worker
 * Agent so that bus-dispatched {@link BusMessage.TaskDispatch} messages reach a
 * real {@code generateReply} execution.
 *
 * <p>Created as a Spring bean only when {@code bus-orc.mode=switch}. Because it
 * is a singleton, its constructor (which subscribes every worker) runs at
 * startup, before any orchestration request — guaranteeing endpoints are live
 * by the time {@link BusAgentDispatcher#dispatch} first fires.
 */
public class BusWorkerEndpointRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BusWorkerEndpointRegistrar.class);

    private final AgentMessageBus bus;
    private final String managerName;
    private final List<BusWorkerEndpoint> endpoints = new ArrayList<>();

    public BusWorkerEndpointRegistrar(AgentMessageBus bus, String managerName) {
        this.bus = bus;
        this.managerName = managerName;
    }

    /** Subscribe a worker agent to the bus under its own name. */
    public BusWorkerEndpointRegistrar register(Agent worker) {
        if (worker == null) return this;
        BusWorkerEndpoint endpoint = new BusWorkerEndpoint(bus, worker, managerName);
        endpoints.add(endpoint);
        log.info("[BusWorkerRegistrar] registered worker endpoint for '{}'", worker.name());
        return this;
    }

    /** All registered endpoints (for tests / inspection). */
    public List<BusWorkerEndpoint> endpoints() {
        return List.copyOf(endpoints);
    }

    /** Cancel every subscription. Intended for test teardown. */
    public void stopAll() {
        endpoints.forEach(BusWorkerEndpoint::stop);
        endpoints.clear();
    }
}
