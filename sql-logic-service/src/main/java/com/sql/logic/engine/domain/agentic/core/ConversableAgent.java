package com.sql.logic.engine.domain.agentic.core;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.bridge.AgentStateBridge;
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
 * Core Agent execution engine.
 * <p>
 * Implements the standardized {@code generateReply()} pipeline:
 * <pre>
 *   loadThinkingMessages() → thinking() → review() → act() → verify()
 * </pre>
 * with a configurable retry loop. Subclasses register domain-specific
 * {@link AgentAction}s and override {@link #buildSystemPrompt} /
 * {@link #buildUserPrompt} to inject role-specific prompt sections.
 * <p>
 * Integration with the existing StateGraph is via {@link #asNodeAction()},
 * which wraps the agent as a {@link NodeAction} that can be added to any
 * {@code StateGraph} node — enabling mixed Agent/Node workflows.
 */
public abstract class ConversableAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ConversableAgent.class);
    private static final Executor VT_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    protected ProfileConfig profile;
    protected AgentMemory memory;
    protected AgentResource resource;
    protected LLMStrategy llmStrategy;
    protected List<AgentAction> actions = new ArrayList<>();
    protected ProfileRenderer profileRenderer = new ProfileRenderer();

    protected int maxRetryCount = 3;
    protected long maxTimeoutSeconds = 600;

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

    public ConversableAgent bind(List<AgentAction> actions) {
        this.actions = actions != null ? new ArrayList<>(actions) : new ArrayList<>();
        return this;
    }

    public ConversableAgent bind(ProfileRenderer renderer) {
        this.profileRenderer = renderer;
        return this;
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

    // --- Accessors for actions and tests ---

    public ProfileConfig getProfile() { return profile; }
    public AgentMemory getMemory() { return memory; }
    public AgentResource getResource() { return resource; }
    public LLMStrategy getLlmStrategy() { return llmStrategy; }
    public List<AgentAction> getActions() { return actions; }

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
    //  Core pipeline — generateReply()
    // ========================================================================

    /**
     * The standardized execution loop.
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

    /**
     * Load the full thinking context: system prompt + memory + resources +
     * task progress + historical dialogues + rely messages + current input.
     */
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

        // 2. Resource prompt
        String resourceContext = (resource != null) ? resource.getPrompt(observation) : "";

        // 3. Build and add system prompt
        String systemPrompt = buildSystemPrompt(observation, memoryContext, resourceContext, context);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(AgentMessage.system(systemPrompt));
        }

        // 4. Task progress (never-lost tracker — independent of message serialization)
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

        // 6. Rely messages (dependency step results)
        if (relyMessages != null) {
            messages.addAll(relyMessages);
        }

        // 7. Current user input
        String userPrompt = buildUserPrompt(observation, memoryContext, resourceContext, context);
        messages.add(AgentMessage.user(userPrompt != null ? userPrompt : observation));

        return messages;
    }

    /**
     * LLM inference stage. Default: concatenate all messages and call LLM.
     * Subclasses may override for streaming or multi-step reasoning.
     */
    protected String thinking(List<AgentMessage> messages) {
        if (llmStrategy == null) {
            throw new IllegalStateException("No LLMStrategy bound to agent " + name());
        }
        String prompt = messagesToPrompt(messages);
        return llmStrategy.generateSql(prompt, null);
    }

    /**
     * Content review stage. Default: always approve.
     * Subclasses may override to add content filtering or safety checks.
     */
    protected ReviewInfo review(String llmOutput) {
        return ReviewInfo.APPROVED;
    }

    /**
     * Action execution stage. Default: execute the first registered action.
     * Subclasses should override to implement domain-specific action selection.
     */
    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.success(message.content(), Map.of())
            );
        }
        return actions.get(0).execute(message, this);
    }

    /**
     * Verification stage. Default: check actionOutput.isSuccess().
     * Subclasses override for domain-specific correctness checks.
     */
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
    //  Abstract methods — subclasses must implement
    // ========================================================================

    /**
     * Build the system prompt from profile, memory, resources, and context.
     */
    protected abstract String buildSystemPrompt(String observation, String memoryContext,
                                                 String resourceContext, Map<String, Object> context);

    /**
     * Build the user-facing prompt for the current observation.
     */
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

    /**
     * Render the profile into a system prompt section using the configured template.
     */
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
        // Default prompt when no template is configured
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
    //  StateGraph integration — asNodeAction()
    // ========================================================================

    /**
     * Wrap this agent as a {@link NodeAction} that can be added to any existing
     * or future {@code StateGraph} as a node.
     * <p>
     * This is the bridge between the Multi-Agent framework and the Spring AI
     * Alibaba StateGraph runtime. When the graph reaches this node:
     * <ol>
     *   <li>{@link AgentStateBridge} converts {@code OverAllState} → {@link AgentMessage}</li>
     *   <li>{@link #generateReply} executes the full pipeline</li>
     *   <li>{@link AgentStateBridge} converts {@link AgentMessage} → state updates</li>
     * </ol>
     * <p>
     * Usage in a StateGraph:
     * <pre>{@code
     *   DataScientistAgent agent = ...;
     *   workflow.addNode("DATA_SCIENTIST", agent.asNodeAction());
     * }</pre>
     */
    public NodeAction asNodeAction() {
        return (OverAllState state) -> {
            AgentMessage input = AgentStateBridge.toAgentMessage(state);
            AgentMessage output = this.generateReply(input, null, null, null).join();
            this.maybeRecordStep(state, output);
            return AgentStateBridge.toStateUpdates(output);
        };
    }

    /**
     * Optionally record a task progress step from the state's current step info.
     */
    private void maybeRecordStep(OverAllState state, AgentMessage output) {
        if (memory == null) return;
        try {
            Object stepObj = state.value("currentStep").orElse(null);
            int step = stepObj instanceof Number n ? n.intValue() : 0;
            String phase = state.value("nextNode").orElse(name()).toString();
            memory.recordTaskProgress(new AgentMemory.TaskProgressEntry(
                    step,
                    output.actionReport() != null ? output.actionReport().content() : output.content(),
                    phase,
                    output.success() ? AgentMemory.TaskStatus.DONE : AgentMemory.TaskStatus.FAILED,
                    output.content()
            ));
        } catch (Exception ignored) {
            // Best-effort progress tracking
        }
    }
}
