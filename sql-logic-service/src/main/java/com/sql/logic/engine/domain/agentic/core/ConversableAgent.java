package com.sql.logic.engine.domain.agentic.core;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.bridge.AgentStateBridge;
import com.sql.logic.engine.domain.agentic.context.ContextBudgetConfig;
import com.sql.logic.engine.domain.agentic.context.ContextManager;
import com.sql.logic.engine.domain.agentic.memory.TaskProgressPersistenceService;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;
import com.sql.logic.engine.domain.agentic.profile.ProfileRenderer;
import com.sql.logic.engine.domain.agentic.resource.AgentResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Core Agent execution engine with Phase 3 context management.
 * <p>
 * Implements the standardized {@code generateReply()} pipeline:
 * <pre>
 *   loadThinkingMessages() → manageContext() → thinking() → review() → act() → verify()
 * </pre>
 * with configurable retry loop and progressive 4-layer context compaction.
 */
public abstract class ConversableAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ConversableAgent.class);
    private static final Executor VT_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    protected ProfileConfig profile;
    protected AgentMemory memory;
    protected AgentResource resource;
    protected List<AgentResource> resources = new ArrayList<>();
    protected LLMStrategy llmStrategy;
    protected LlmClientManager llmClientManager;
    protected List<AgentAction> actions = new ArrayList<>();
    protected ProfileRenderer profileRenderer = new ProfileRenderer();

    protected int maxRetryCount = 3;
    protected long maxTimeoutSeconds = 600;

    // Context management and persistence
    protected ContextManager contextManager;
    protected TaskProgressPersistenceService persistenceService;

    // --- Fluent binding API ---

    public ConversableAgent bind(ProfileConfig profile) {
        this.profile = profile;
        return this;
    }

    public ConversableAgent bind(AgentMemory memory) {
        this.memory = memory;
        return this;
    }

    public ConversableAgent bind(AgentResource resource) {
        this.resource = resource;
        return this;
    }

    public ConversableAgent bind(LLMStrategy llmStrategy) {
        this.llmStrategy = llmStrategy;
        return this;
    }

    /**
     * Bind a {@link LlmClientManager} for lazy LLM strategy resolution.
     * When set, {@link #thinking(List)} resolves the strategy at call time
     * via {@code llmClientManager.getClient(0L)} if no direct strategy is bound.
     * This avoids bean-initialization ordering issues.
     */
    public ConversableAgent bind(LlmClientManager llmClientManager) {
        this.llmClientManager = llmClientManager;
        return this;
    }

    public ConversableAgent bind(List<AgentAction> actions) {
        this.actions = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
        return this;
    }

    public ConversableAgent bindResources(List<AgentResource> resources) {
        this.resources = resources != null ? new ArrayList<>(resources) : new ArrayList<>();
        return this;
    }

    public ConversableAgent bind(ProfileRenderer renderer) {
        this.profileRenderer = renderer;
        return this;
    }

    /**
     * Bind a context manager for multi-layer compaction.
     */
    public ConversableAgent bindContextManager(ContextManager manager) {
        this.contextManager = manager;
        return this;
    }

    /**
     * Bind a persistence service for task progress snapshots.
     */
    public ConversableAgent bindPersistence(TaskProgressPersistenceService service) {
        this.persistenceService = service;
        return this;
    }

    /**
     * Initialize context management with defaults derived from agent context.
     */
    public void initContextManagement() {
        if (contextManager == null) {
            contextManager = new ContextManager(new ContextBudgetConfig());
        }
        log.info("Context management enabled for agent {}", name());
    }

    /**
     * Finalize binding. Subclasses may override for validation.
     */
    public ConversableAgent build() {
        if (profile == null) {
            throw new IllegalStateException("ProfileConfig must be bound before build()");
        }
        return this;
    }

    // --- Accessors ---

    public ProfileConfig getProfile() { return profile; }
    public AgentMemory getMemory() { return memory; }
    public AgentResource getResource() { return resource; }
    public LLMStrategy getLlmStrategy() { return llmStrategy; }
    public List<AgentAction> getActions() { return actions; }
    public ContextManager getContextManager() { return contextManager; }

    // --- Agent interface: identity ---

    @Override
    public String name() { return profile != null ? profile.name() : getClass().getSimpleName(); }

    @Override
    public String role() { return profile != null ? profile.role() : ""; }

    @Override
    public String goal() { return profile != null ? profile.goal() : ""; }

    @Override
    public List<String> constraints() { return profile != null ? profile.constraints() : List.of(); }

    @Override
    public String description() { return profile != null ? profile.description() : ""; }

    // --- Agent interface: messaging ---

    @Override
    public CompletableFuture<Void> send(AgentMessage message, Agent recipient) {
        return CompletableFuture.runAsync(() -> {
            if (recipient != null) {
                recipient.receive(message, this);
            }
        }, VT_EXECUTOR);
    }

    @Override
    public CompletableFuture<Void> receive(AgentMessage message, Agent sender) {
        return CompletableFuture.runAsync(() -> {
            log.debug("[{}] Received message from {}: {}", name(), sender != null ? sender.name() : "null",
                    message.content() != null ? message.content().substring(0, Math.min(80, message.content().length())) : "");
            if (memory != null) {
                memory.write(MemoryFragment.of(
                        "Received from " + (sender != null ? sender.name() : "user") + ": " + message.content(),
                        "EPISODIC", 0.5
                ));
            }
        }, VT_EXECUTOR);
    }

    // ========================================================================
    //  Core pipeline — generateReply() with Phase 3 context management
    // ========================================================================

    /**
     * The standardized execution loop with progressive context compaction.
     */
    @Override
    public CompletableFuture<AgentMessage> generateReply(
            AgentMessage receivedMessage,
            Agent sender,
            List<AgentMessage> relyMessages,
            List<AgentMessage> historicalDialogues) {

        return CompletableFuture.supplyAsync(() -> {
            AgentMessage replyMessage = initReplyMessage(receivedMessage, sender);
            String observation = receivedMessage != null ? receivedMessage.content() : "";
            String failReason = null;
            long startTime = System.currentTimeMillis();

            for (int retry = 0; retry < maxRetryCount; retry++) {
                try {
                    // Step 1: Load thinking context
                    List<AgentMessage> thinkingMessages = loadThinkingMessages(
                            receivedMessage, sender, observation,
                            relyMessages, historicalDialogues,
                            replyMessage.context()
                    );

                    // Context budget management — compact if needed
                    if (contextManager != null) {
                        String taskProgress = memory != null ? memory.getTaskProgressSummary() : null;
                        thinkingMessages = contextManager.manageContext(
                                thinkingMessages, retry, taskProgress);
                    }

                    // Step 2: Thinking (LLM inference)
                    String llmOutput = thinking(thinkingMessages);
                    replyMessage = replyMessage.withContent(llmOutput);

                    // Step 3: Review
                    ReviewInfo review = review(llmOutput);
                    replyMessage = replyMessage.withReviewInfo(review);

                    if (!review.approved()) {
                        failReason = "Review rejected: " + review.comments();
                        observation = failReason;
                        writeMemories(receivedMessage, llmOutput, null, false, failReason, retry);
                        continue;
                    }

                    // Step 4: Act
                    ActionOutput actionOut = act(replyMessage, sender).join();
                    replyMessage = replyMessage.withActionReport(actionOut);

                    // Step 5: Verify
                    VerifyResult verifyResult = verify(replyMessage, sender).join();

                    if (verifyResult.passed()) {
                        writeMemories(receivedMessage, llmOutput, actionOut, true, null, retry);
                        replyMessage = replyMessage.withSuccess(true);
                        break;
                    } else {
                        failReason = verifyResult.reason();
                        observation = (actionOut != null && actionOut.hasRetry())
                                ? failReason
                                : observation;
                        writeMemories(receivedMessage, llmOutput, actionOut, false, failReason, retry);
                    }

                    // Timeout check
                    if (System.currentTimeMillis() - startTime > maxTimeoutSeconds * 1000) {
                        log.warn("[{}] generateReply timeout after {}s", name(), maxTimeoutSeconds);
                        break;
                    }

                } catch (Exception e) {
                    log.warn("[{}] generateReply error (retry {}/{}): {}", name(), retry, maxRetryCount, e.getMessage());
                    failReason = e.getMessage();

                    // Reactive compaction on context_too_long errors
                    if (isContextTooLongError(e) && contextManager != null) {
                        try {
                            var messages = loadThinkingMessages(
                                    receivedMessage, sender, observation,
                                    relyMessages, historicalDialogues,
                                    replyMessage.context()
                            );
                            var compacted = contextManager.reactiveCompact(messages);
                            // Retry with compacted context
                            String llmOutput = thinking(compacted);
                            replyMessage = replyMessage.withContent(llmOutput);
                            ReviewInfo review = review(llmOutput);
                            if (review.approved()) {
                                ActionOutput actionOut = act(replyMessage, sender).join();
                                VerifyResult verifyResult = verify(replyMessage, sender).join();
                                if (verifyResult.passed()) {
                                    writeMemories(receivedMessage, llmOutput, actionOut, true, null, retry);
                                    replyMessage = replyMessage.withSuccess(true).withActionReport(actionOut);
                                    break;
                                }
                            }
                        } catch (Exception ignored) {
                            // Fall through to normal retry
                        }
                    }

                    observation = failReason;
                    try {
                        writeMemories(receivedMessage, null, null, false, failReason, retry);
                    } catch (Exception ignored) {}
                }
            }

            return replyMessage;
        }, VT_EXECUTOR);
    }

    // ========================================================================
    //  Pipeline stages — overridable by subclasses
    // ========================================================================

    protected List<AgentMessage> loadThinkingMessages(
            AgentMessage receivedMessage,
            Agent sender,
            String observation,
            List<AgentMessage> relyMessages,
            List<AgentMessage> historicalDialogues,
            Map<String, Object> context) {

        List<AgentMessage> messages = new ArrayList<>();

        // 1. Memory recall
        String memoryContext = (memory != null) ? memory.read(observation) : "";

        // 2. Resource prompts (multi-resource injection)
        StringBuilder resourceCtx = new StringBuilder();
        if (resource != null) {
            String rp = resource.getPrompt(observation);
            if (rp != null && !rp.isBlank()) resourceCtx.append(rp).append("\n");
        }
        for (AgentResource r : resources) {
            String rp = r.getPrompt(observation);
            if (rp != null && !rp.isBlank()) resourceCtx.append(rp).append("\n");
        }
        String resourceContext = resourceCtx.toString();

        // 3. Build and add system prompt
        String systemPrompt = buildSystemPrompt(observation, memoryContext, resourceContext, context);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(AgentMessage.system(systemPrompt));
        }

        // 4. Task progress (never-lost tracker)
        if (memory != null) {
            String taskProgress = memory.getTaskProgressSummary();
            if (taskProgress != null) {
                messages.add(AgentMessage.system(taskProgress));
            }
        }

        // 5. Historical dialogues
        if (historicalDialogues != null) {
            messages.addAll(historicalDialogues);
        }

        // 6. Rely messages
        if (relyMessages != null) {
            messages.addAll(relyMessages);
        }

        // 7. Current user input
        String userPrompt = buildUserPrompt(observation, memoryContext, resourceContext, context);
        messages.add(AgentMessage.user(userPrompt != null ? userPrompt : observation));

        return messages;
    }

    protected String thinking(List<AgentMessage> messages) {
        LLMStrategy strategy = resolveLlmStrategy();
        if (strategy == null) {
            throw new IllegalStateException("No LLMStrategy bound to agent " + name()
                    + " — bind either a direct LLMStrategy or a LlmClientManager for lazy resolution");
        }
        String prompt = messagesToPrompt(messages);
        return strategy.chat(prompt);
    }

    /**
     * Resolve the effective LLM strategy: direct binding takes precedence,
     * otherwise fall back to lazy resolution via {@link LlmClientManager#getClient(Long)}
     * with the system default key (0L).
     */
    private LLMStrategy resolveLlmStrategy() {
        if (llmStrategy != null) return llmStrategy;
        if (llmClientManager != null) return llmClientManager.getClient(0L);
        return null;
    }

    protected ReviewInfo review(String llmOutput) {
        return ReviewInfo.APPROVED;
    }

    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.success(message.content(), Map.of())
            );
        }
        return actions.get(0).execute(message, this);
    }

    @Override
    public CompletableFuture<VerifyResult> verify(AgentMessage message, Agent sender) {
        ActionOutput ao = message.actionReport();
        if (ao != null && ao.isExeSuccess()) {
            return CompletableFuture.completedFuture(VerifyResult.PASSED);
        }
        return CompletableFuture.completedFuture(
                VerifyResult.fail(ao != null ? ao.content() : "No action output")
        );
    }

    // ========================================================================
    //  Abstract methods
    // ========================================================================

    protected abstract String buildSystemPrompt(String observation, String memoryContext,
                                                 String resourceContext, Map<String, Object> context);

    protected abstract String buildUserPrompt(String observation, String memoryContext,
                                               String resourceContext, Map<String, Object> context);

    // ========================================================================
    //  Memory helpers
    // ========================================================================

    protected void writeMemories(AgentMessage received, String llmOutput, ActionOutput actionOut,
                                  boolean success, String failReason, int retry) {
        if (memory == null) return;
        try {
            String question = received != null ? received.content() : "";
            String summary = success
                    ? "[SUCCESS] Q: " + question + " → " + (actionOut != null ? actionOut.content() : llmOutput)
                    : "[FAILED retry=" + retry + "] Q: " + question + " reason=" + failReason;

            MemoryFragment frag = MemoryFragment.of(
                    summary,
                    "TASK",
                    success ? 0.7 : 0.3
            );
            memory.write(frag);
        } catch (Exception e) {
            log.debug("[{}] Memory write skipped: {}", name(), e.getMessage());
        }
    }

    protected AgentMessage initReplyMessage(AgentMessage received, Agent sender) {
        return AgentMessage.builder()
                .senderName(name())
                .senderRole(role())
                .rounds(received != null ? received.rounds() + 1 : 1)
                .success(false)
                .build();
    }

    // ========================================================================
    //  Prompt assembly
    // ========================================================================

    protected String messagesToPrompt(List<AgentMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (AgentMessage msg : messages) {
            switch (msg.messageType()) {
                case SYSTEM -> {
                    sb.append(msg.content());
                    sb.append("\n\n");
                }
                case USER -> {
                    sb.append("User: ").append(msg.content()).append("\n");
                }
                case AI -> {
                    sb.append("Assistant: ").append(msg.content()).append("\n");
                }
                case TOOL -> {
                    sb.append("Tool Output: ").append(msg.content()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    protected String renderProfilePrompt() {
        if (profile == null) return "";
        if (profile.systemPromptTemplate() != null && !profile.systemPromptTemplate().isBlank()) {
            return profileRenderer.render(profile.systemPromptTemplate(), Map.of(
                    "name", profile.name(),
                    "role", profile.role(),
                    "goal", profile.goal(),
                    "constraints", String.join(", ", profile.constraints()),
                    "description", profile.description()
            ));
        }
        return """
                You are %s, a %s.
                Your goal: %s
                Constraints: %s
                """.formatted(
                profile.name(), profile.role(), profile.goal(),
                String.join(", ", profile.constraints())
        );
    }

    // ========================================================================
    //  Context error detection
    // ========================================================================

    /**
     * Detect whether an exception indicates a context-too-long error from the LLM.
     * Subclasses may override to add provider-specific detection.
     */
    protected boolean isContextTooLongError(Exception e) {
        if (e == null) return false;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("context_length_exceeded")
                || msg.contains("context too long")
                || msg.contains("maximum context length")
                || msg.contains("reduce the length")
                || msg.contains("token limit")
                || msg.contains("4003")
                || (e.getCause() != null && isContextTooLongError((Exception) e.getCause()));
    }

    // ========================================================================
    //  StateGraph integration — asNodeAction()
    // ========================================================================

    public NodeAction asNodeAction() {
        return (OverAllState state) -> {
            AgentMessage input = AgentStateBridge.toAgentMessage(state);
            AgentMessage output = this.generateReply(input, null, null, null).join();
            this.maybeRecordStep(state, output);
            return AgentStateBridge.toStateUpdates(output);
        };
    }

    /**
     * Record a task progress step and persist to database.
     */
    private void maybeRecordStep(OverAllState state, AgentMessage output) {
        if (memory == null) return;
        try {
            Object stepObj = state.value("currentStep").orElse(null);
            int step = stepObj instanceof Number n ? n.intValue() : 0;
            String phase = state.value("nextNode").orElse(name()).toString();
            AgentMemory.TaskProgressEntry entry = new AgentMemory.TaskProgressEntry(
                    step,
                    output.actionReport() != null ? output.actionReport().content() : output.content(),
                    phase,
                    output.success() ? AgentMemory.TaskStatus.DONE : AgentMemory.TaskStatus.FAILED,
                    output.content()
            );
            memory.recordTaskProgress(entry);

            // Context persistence
            if (persistenceService != null) {
                Object convIdObj = state.value("threadId").orElse(null);
                String convId = convIdObj != null ? convIdObj.toString() : null;
                persistenceService.persistAsync(convId, entry);
            }
        } catch (Exception ignored) {
            // Best-effort progress tracking
        }
    }
}
