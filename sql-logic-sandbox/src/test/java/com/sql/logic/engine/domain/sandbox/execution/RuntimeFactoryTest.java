package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RuntimeFactory} 4-way selection logic — Docker → Podman →
 * Nerdctl → Local (opt-in) → Fail-closed. Aligns with DB-GPT's
 * {@code RuntimeFactory.create()} priority.
 *
 * <p>Uses stub runtimes that override {@code isCliAvailable()} so the tests don't
 * depend on real container daemons.
 */
class RuntimeFactoryTest {

    /** Stub Docker runtime whose availability is controlled by the test. */
    static class StubDockerRuntime extends DockerSandboxRuntime {
        private final boolean available;
        StubDockerRuntime(boolean available) {
            super(new SandboxProperties());
            this.available = available;
        }
        @Override
        public boolean isCliAvailable() { return available; }
    }

    /** Stub Podman runtime whose availability is controlled by the test. */
    static class StubPodmanRuntime extends PodmanSandboxRuntime {
        private final boolean available;
        StubPodmanRuntime(boolean available) {
            super(new SandboxProperties());
            this.available = available;
        }
        @Override
        public boolean isCliAvailable() { return available; }
    }

    /** Stub Nerdctl runtime whose availability is controlled by the test. */
    static class StubNerdctlRuntime extends NerdctlSandboxRuntime {
        private final boolean available;
        StubNerdctlRuntime(boolean available) {
            super(new SandboxProperties());
            this.available = available;
        }
        @Override
        public boolean isCliAvailable() { return available; }
    }

    private SandboxProperties props(boolean allowLocal) {
        SandboxProperties p = new SandboxProperties();
        p.setAllowLocalRuntime(allowLocal);
        return p;
    }

    private RuntimeFactory factory(SandboxProperties props,
                                   boolean docker, boolean podman, boolean nerdctl) {
        RuntimeFactory f = new RuntimeFactory(
                props,
                new StubDockerRuntime(docker),
                new StubPodmanRuntime(podman),
                new StubNerdctlRuntime(nerdctl),
                new LocalSandboxRuntime());
        f.selectRuntime();
        return f;
    }

    // ---- Fail-closed ----

    @Test
    void shouldFailClosedWhenNoRuntimeAvailableAndLocalNotOptedIn() {
        RuntimeFactory factory = factory(props(false), false, false, false);

        assertFalse(factory.isAvailable());
        assertEquals("none", factory.selectedRuntimeId());
        assertThrows(IllegalStateException.class, factory::getRuntime);
        assertNotNull(factory.selectionReason());
    }

    // ---- Docker (preferred) ----

    @Test
    void shouldSelectDockerWhenAvailable() {
        RuntimeFactory factory = factory(props(false), true, false, false);

        assertTrue(factory.isAvailable());
        assertEquals("docker", factory.selectedRuntimeId());
        assertTrue(factory.getRuntime() instanceof DockerSandboxRuntime);
    }

    @Test
    void shouldPreferDockerOverPodmanAndNerdctl() {
        RuntimeFactory factory = factory(props(false), true, true, true);

        assertEquals("docker", factory.selectedRuntimeId());
    }

    // ---- Podman (second priority) ----

    @Test
    void shouldSelectPodmanWhenDockerUnavailableAndPodmanAvailable() {
        RuntimeFactory factory = factory(props(false), false, true, false);

        assertTrue(factory.isAvailable());
        assertEquals("podman", factory.selectedRuntimeId());
        assertTrue(factory.getRuntime() instanceof PodmanSandboxRuntime);
    }

    @Test
    void shouldPreferPodmanOverNerdctl() {
        RuntimeFactory factory = factory(props(false), false, true, true);

        assertEquals("podman", factory.selectedRuntimeId());
    }

    // ---- Nerdctl (third priority) ----

    @Test
    void shouldSelectNerdctlWhenDockerAndPodmanUnavailable() {
        RuntimeFactory factory = factory(props(false), false, false, true);

        assertTrue(factory.isAvailable());
        assertEquals("nerdctl", factory.selectedRuntimeId());
        assertTrue(factory.getRuntime() instanceof NerdctlSandboxRuntime);
    }

    // ---- Local (opt-in, last resort) ----

    @Test
    void shouldSelectLocalWhenOptedInAndNoContainerRuntime() {
        RuntimeFactory factory = factory(props(true), false, false, false);

        assertTrue(factory.isAvailable());
        assertEquals("local", factory.selectedRuntimeId());
        assertSame(LocalSandboxRuntime.class, factory.getRuntime().getClass());
    }

    @Test
    void shouldPreferAnyContainerRuntimeOverLocalEvenWhenLocalOptedIn() {
        // Docker preferred over local
        assertEquals("docker", factory(props(true), true, false, false).selectedRuntimeId());
        // Podman preferred over local
        assertEquals("podman", factory(props(true), false, true, false).selectedRuntimeId());
        // Nerdctl preferred over local
        assertEquals("nerdctl", factory(props(true), false, false, true).selectedRuntimeId());
    }
}
