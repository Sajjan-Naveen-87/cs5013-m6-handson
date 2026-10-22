import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal ReAct-style agent loop.
 *
 * Each turn we send the LLM the running scratchpad ("Thought/Action/
 * Observation" transcript so far) plus the user's original query, parse
 * exactly one Action out of the LLM's response, call the corresponding
 * tool, append the Observation, and loop.
 *
 * Two hard budgets stop a confused agent:
 *  - stepBudget: maximum number of LLM calls per run;
 *  - tokenBudget: maximum total tokens (prompt + completion) per run,
 *    checked before every LLM call; {@code <= 0} means unlimited.
 *    Real counts come from {@link LlmClient#lastTokenCount()} when the
 *    client reports them, otherwise a chars/4 estimate is used.
 *
 * The same transcript is offered to the LLM two ways: as the flat
 * template (with {scratchpad} filled in) and as chat turns (instructions
 * as the system message, each model turn as an assistant message, each
 * Observation as a user message). See {@link LlmClient#complete(String, List)}.
 *
 * Repeated-call guard: if the model issues a tool call identical to one
 * it already made, the tool is not re-run; the Observation instead tells
 * the model the result is already in the transcript and to answer.
 * Small models otherwise tend to repeat their first action forever.
 *
 * Every run writes a human-readable trace (prompt size, model output,
 * tool call, observation, running token total, stop reason) to the
 * trace file if one is set.
 */
public class AgentDriver {

    /** Why the last {@link #run} ended. */
    public enum StopReason { FINAL_ANSWER, STEP_BUDGET, TOKEN_BUDGET, NO_ACTION }

    private static final Pattern ACTION = Pattern.compile("(?im)^\\s*\\**Action\\**\\s*:\\s*(.+?)\\s*$");
    private static final Pattern ACTION_INPUT = Pattern.compile("(?im)^\\s*\\**Action Input\\**\\s*:\\s*(.*?)\\s*$");
    private static final Pattern FINAL = Pattern.compile("(?im)^\\s*\\**Final Answer\\**\\s*:");
    private static final Pattern OBSERVATION = Pattern.compile("(?im)^\\s*\\**Observation\\**\\s*:");
    /** "search_books(Le Guin|1970|1979)" style: tool name with the argument inline. */
    private static final Pattern INLINE_CALL = Pattern.compile("^([A-Za-z_]+)\\s*\\((.*)\\)$");

    private final LlmClient llm;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Path promptTemplate;
    private final int stepBudget;
    private final int tokenBudget;

    private Path traceFile;
    private StopReason lastStopReason;
    private int tokensUsed;
    private int steps;
    private final List<String> toolCalls = new ArrayList<>();

    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget) {
        this(llm, promptTemplate, stepBudget, 0);
    }

    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget, int tokenBudget) {
        if (stepBudget < 1) throw new IllegalArgumentException("stepBudget must be >= 1");
        this.llm = llm;
        this.promptTemplate = promptTemplate;
        this.stepBudget = stepBudget;
        this.tokenBudget = tokenBudget;
        registerTool(new ListBooksTool());
        registerTool(new SearchBooksTool());
    }

    public void registerTool(Tool t) {
        tools.put(t.name(), t);
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    /** Write a trace log of each run to this file (overwritten per run). */
    public void setTraceFile(Path traceFile) {
        this.traceFile = traceFile;
    }

    public StopReason getLastStopReason() { return lastStopReason; }
    public int getTokensUsed() { return tokensUsed; }
    public int getSteps() { return steps; }
    /** "name(arg) -> N chars" for every tool call of the last run. */
    public List<String> getToolCalls() { return toolCalls; }

    /**
     * Run the loop. Returns the final scratchpad (transcript of all
     * thoughts, actions, observations, plus the last LLM response).
     */
    public String run(String userQuery) throws IOException {
        String template = Files.readString(promptTemplate);
        StringBuilder scratch = new StringBuilder();
        StringBuilder trace = new StringBuilder();
        tokensUsed = 0;
        steps = 0;
        toolCalls.clear();
        lastStopReason = null;
        Set<String> seenCalls = new HashSet<>();
        List<ChatTurn> chat = new ArrayList<>();
        chat.add(ChatTurn.system(template
                .replace("{query}", userQuery)
                .replace("{scratchpad}", "(the messages below)")));
        chat.add(ChatTurn.user("User query: " + userQuery));

        trace.append("QUERY: ").append(userQuery).append('\n')
             .append("PROMPT TEMPLATE: ").append(promptTemplate).append('\n')
             .append("BUDGETS: steps=").append(stepBudget)
             .append(", tokens=").append(tokenBudget > 0 ? String.valueOf(tokenBudget) : "unlimited")
             .append("\n\n");

        for (int step = 0; step < stepBudget; step++) {
            String prompt = template
                    .replace("{query}", userQuery)
                    .replace("{scratchpad}", scratch.toString());

            // Cost budget: refuse to make a call that would overrun it.
            int promptEstimate = estimateTokens(prompt);
            if (tokenBudget > 0 && tokensUsed + promptEstimate > tokenBudget) {
                lastStopReason = StopReason.TOKEN_BUDGET;
                String msg = "ABORTED: token budget " + tokenBudget + " would be exceeded (used "
                        + tokensUsed + ", next prompt ~" + promptEstimate + ")";
                scratch.append("Observation: ").append(msg).append('\n');
                trace.append("=== step ").append(step + 1).append('\n').append(msg).append('\n');
                break;
            }

            String raw = llm.complete(prompt, List.copyOf(chat));
            steps++;
            int used = llm.lastTokenCount();
            if (used < 0) used = promptEstimate + estimateTokens(raw);
            tokensUsed += used;

            String response = trimResponse(raw);
            scratch.append(response).append('\n');
            chat.add(ChatTurn.assistant(response));
            trace.append("=== step ").append(step + 1)
                 .append(" (prompt ").append(prompt.length()).append(" chars; this call ")
                 .append(used).append(" tok; total ").append(tokensUsed).append(" tok)\n")
                 .append(response).append('\n');
            if (!response.equals(raw == null ? "" : raw.strip())) {
                trace.append("[driver] dropped model-invented text after the first action\n");
            }

            if (hasFinalAnswer(response)) {
                lastStopReason = StopReason.FINAL_ANSWER;
                break;
            }
            if (shouldStop(response, step)) {
                lastStopReason = StopReason.STEP_BUDGET;
                String msg = "ABORTED: step budget of " + stepBudget + " exhausted without a Final Answer";
                scratch.append("Observation: ").append(msg).append('\n');
                trace.append(msg).append('\n');
                break;
            }
            Map.Entry<String, String> action = parseAction(response);
            if (action == null) {
                lastStopReason = StopReason.NO_ACTION;
                scratch.append("Observation: (no action parsed; stopping)\n");
                trace.append("[driver] no action parsed; stopping\n");
                break;
            }
            Tool t = tools.get(action.getKey());
            String callKey = action.getKey() + "\u0000" + action.getValue();
            String obs;
            if (t != null && !seenCalls.add(callKey)) {
                obs = "REPEATED CALL: " + action.getKey() + "(\"" + action.getValue() + "\") was already made;"
                        + " its result is in the transcript above. Do not call it again --"
                        + " use that result and reply with Thought + Final Answer.";
                trace.append("[driver] repeated call blocked (not re-run)\n");
            } else {
                obs = (t == null)
                        ? "ERROR: unknown tool " + action.getKey() + "; valid tools: " + tools.keySet()
                        : t.call(action.getValue());
                toolCalls.add(action.getKey() + "(\"" + action.getValue() + "\") -> " + obs.length() + " chars");
                trace.append("[tool] ").append(action.getKey()).append("(\"").append(action.getValue())
                     .append("\") -> ").append(obs.length()).append(" chars\n");
            }
            scratch.append("Observation: ").append(obs).append('\n');
            chat.add(ChatTurn.user("Observation: " + obs));
            trace.append("Observation: ").append(obs).append('\n');

            if (tokenBudget > 0 && tokensUsed >= tokenBudget) {
                lastStopReason = StopReason.TOKEN_BUDGET;
                String msg = "ABORTED: token budget " + tokenBudget + " exhausted (used " + tokensUsed + ")";
                scratch.append("Observation: ").append(msg).append('\n');
                trace.append(msg).append('\n');
                break;
            }
        }

        trace.append("\nSTOP: ").append(lastStopReason)
             .append(" | LLM calls: ").append(steps)
             .append(" | tool calls: ").append(toolCalls.size())
             .append(" | tokens: ").append(tokensUsed).append('\n');
        for (String c : toolCalls) trace.append("  ").append(c).append('\n');
        if (traceFile != null) {
            if (traceFile.getParent() != null) Files.createDirectories(traceFile.getParent());
            Files.writeString(traceFile, trace.toString());
        }
        return scratch.toString();
    }

    /**
     * True once the LLM has emitted a "Final Answer:" (with no action
     * before it) or this was the last step the budget allows.
     */
    protected boolean shouldStop(String lastResponse, int step) {
        return hasFinalAnswer(lastResponse) || step >= stepBudget - 1;
    }

    /**
     * Parse the first "Action: <name>" / "Action Input: <arg>" pair out of
     * {@code response} and return them as a (name, arg) entry, or null if
     * there is no Action line. Tolerates backticks/quotes/markdown bold,
     * a missing Action Input (empty arg), and an inline call such as
     * {@code Action: search_books(Le Guin|1970|1979)}.
     */
    protected Map.Entry<String, String> parseAction(String response) {
        if (response == null) return null;
        Matcher a = ACTION.matcher(response);
        if (!a.find()) return null;
        String name = unquote(a.group(1));
        String arg = "";
        Matcher inline = INLINE_CALL.matcher(name);
        if (inline.matches()) {
            name = inline.group(1);
            arg = unquote(inline.group(2));
        }
        Matcher in = ACTION_INPUT.matcher(response);
        if (in.find(a.end())) {
            arg = unquote(in.group(1));
        }
        return new AbstractMap.SimpleEntry<>(name.strip(), arg);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Final Answer present and not preceded by an Action in the same turn. */
    static boolean hasFinalAnswer(String response) {
        if (response == null) return false;
        Matcher f = FINAL.matcher(response);
        if (!f.find()) return false;
        Matcher a = ACTION.matcher(response);
        return !a.find() || a.start() > f.start();
    }

    /**
     * Small models often keep going after their Action and invent the
     * Observation (and sometimes a Final Answer built on it). Keep only
     * the text before any invented Observation / post-action Final
     * Answer, so the real tool result is the only Observation the model
     * ever sees.
     */
    static String trimResponse(String raw) {
        String r = raw == null ? "" : raw.strip();
        Matcher obs = OBSERVATION.matcher(r);
        if (obs.find()) r = r.substring(0, obs.start()).strip();
        Matcher a = ACTION.matcher(r);
        Matcher f = FINAL.matcher(r);
        if (a.find() && f.find() && f.start() > a.start()) {
            r = r.substring(0, f.start()).strip();
        }
        return r;
    }

    private static String unquote(String s) {
        s = s.strip();
        while (s.length() >= 2 && (s.startsWith("`") || s.startsWith("\"") || s.startsWith("'"))
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s.replace("`", "").replace("*", "").strip();
    }

    /** Rough token estimate (~4 characters per token) for clients that do not report usage. */
    static int estimateTokens(String s) {
        return s == null ? 0 : (s.length() + 3) / 4;
    }
}
