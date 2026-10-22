# Session 6B: Agentic mini-pipeline (reflection)

**Repository:** https://github.com/Sajjan-Naveen-87/cs5013-m6-handson (Session 6B is in `m6-lab-b/`)

**Setup.** OpenJDK 21, langchain4j 0.36.0 (`make langchain4j`).
- **LLM:** `OllamaLlm` with **llama3.2:3b** (the 8 GB-RAM model from the handout), temperature 0, run locally by Ollama 0.40.4 on a Quadro P4000 GPU.
- **Default budgets:** 8 steps and an unlimited token budget, unless an experiment says otherwise.
- **Traces:** every experiment's trace log is under `traces/`.
- **Tests:** `make test` passes 10 tests: the 2 seed tests plus 8 of mine, which cover the tools, `parseAction`, `shouldStop`, and both budgets using a scripted LLM.

Correct answers from `books.json`, for checking the agent:
- **Question A**, Le Guin in the 1970s: **1** (*The Dispossessed*, 1974). Her other two books are from 1968 and 1969.
- **Q-B**, books published in the 1990s: **5** (*Parable of the Sower*, *Snow Crash*, *Cryptonomicon*, *The God of Small Things*, *A Suitable Boy*).

## What was built

- **`ListBooksTool`** returns `books.json` verbatim (3,351 chars, roughly 1,250 tokens).
- **`SearchBooksTool`** parses `author|year_from|year_to`; empty parts mean "no constraint" and years are inclusive. It returns only the matching records as a JSON array, or `[]`.
  - Author matching is by word and ignores case and initials, so "Ursula Le Guin" matches "Ursula K. Le Guin".
  - Non-numeric years return an `ERROR:` message that the model can see.
- **`AgentDriver`**
  - **`shouldStop`** returns true on a `Final Answer:` with no Action before it, or at the last step the budget allows.
  - **`parseAction`** extracts the first `Action:` / `Action Input:` pair. It tolerates quotes, backticks, markdown bold, a missing input, and the inline form `search_books(...)`.
  - **Budgets:**
    - A **step budget** caps the number of LLM calls.
    - A **token budget** caps the total of prompt + completion tokens. It is checked *before* each call, so the driver never makes a call that would overrun it. Counts come from Ollama when it reports them (`LlmClient.lastTokenCount()`); otherwise a chars/4 estimate is used.
    - Both budgets stop cleanly with an `ABORTED: …` observation and a `STOP:` reason.
  - **Trace log:** every step records the prompt size, tokens for that call, the running total, the model's text, the tool call and its observation, and the stop reason.
  - **Safeguards I added after seeing failures (task 3):**
    - Text the model writes after its Action, such as a made-up "Observation:", is dropped. Only the real tool output is ever shown back.
    - A **repeated-call guard**: an identical tool call is not re-run. The model is told the result is already in the transcript.
    - The transcript is also sent as **chat turns**: the instructions as the system message, each model turn as an assistant message, and each Observation as a user message. `StubLlmClient` still receives the original flat prompt.
- **`Main`** (`make run ARGS='…'`) is the construction site that uses `new OllamaLlm()`. It takes `q=`, `prompt=`, `steps=`, `tokens=` and `trace=` options.

## Task 3: Question A, "How many books did Ursula Le Guin write in the 1970s?"

Final run (`traces/03-question-A.log`):

| | |
|---|---|
| Tool called | `search_books` |
| Arguments | `Ursula Le Guin\|1970\|1979` |
| Loop iterations | **2 LLM calls** (1 tool call, then Final Answer) |
| Tokens | 922 |
| Answer | `Final Answer: 1 (The Dispossessed)`, which is **correct** |

Getting there took several attempts. With the starter prompt sent as one flat text, the 3B model never stopped on its own (traces `03a`–`03d`):

| Trace | Prompt / driver | What happened |
|---|---|---|
| `03a` | starter prompt, flat | Correct `search_books` call first. Then `list_books` "because the result might be too specific", then the same search 6 more times. **Hit the 8-step budget, 12,056 tokens, no answer.** |
| `03b` | + "never repeat a tool call" rule | Worse: 4 `list_books` calls, 24,809 tokens, no answer. |
| `03c` | + one worked example | Answered "1" only on step 8, after 7 tool calls and 25,137 tokens. Its Thought said "I can answer now" and the very next line was another Action. |
| `03d` | "Form A / Form B" prompt | Repeated the identical search 7 times. |
| `03e` | starter prompt, **chat turns** | Answered 1 in 3 calls, but made one needless `list_books` call. Its Thought claimed "Le Guin wrote more than one book in the 1970s", which is false. |
| `03` | Form A/B prompt + example, **chat turns** + repeat guard | **2 calls, 922 tokens, correct.** |

When I tested the same step-2 prompt directly against Ollama, the flat prompt made the model copy its first turn back verbatim. The same content sent as chat turns gave `Final Answer: 1 (The Dispossessed)` straight away. The final `prompts/react.txt` is the starter prompt with three changes:
- the two answer forms are spelled out explicitly,
- one worked example is added (about Asimov, so it gives nothing away),
- a closing "Your next turn" cue is added.

The tool descriptions are unchanged from the starter. The starter prompt is kept as `prompts/react-starter.txt`.

## Task 4: vague tool descriptions

Each variant was run on question A and on Q-B ("Which books in the catalogue were published in the 1990s?"). Q-B is a year-only filter, so it needs the "empty part = no constraint" rule. Traces are `traces/04-*.log`; prompts are `prompts/react-vague*.txt` and `react-noex-*.txt`.

**With the worked example in the prompt:**

| Variant | Q-A tool call → answer | Q-B tool call → answer |
|---|---|---|
| Sharp (final prompt) | `search_books("Ursula Le Guin\|1970\|1979")` → **1** ✔ | `search_books("\|1990\|2000")` → **5** ✔ |
| **Vague 1 (handout's):** search_books = "Search for books." (format line kept, "empty parts mean no constraint" removed) | same search → 1 ✔ | `search_books("year_from\|1990\|1999")` → `[]` → **"0"** ✘ |
| **Vague 2:** both tools vague; list_books loses its "expensive" warning | same search → 1 ✔ | `search_books("\|1990\|1999")` → 5 ✔ |
| **Vague 3:** search_books = "Search for books." with **no argument format** | same search → 1 ✔ | `search_books("\|1990\|1999")` → 5 ✔ |
| **Vague 4 (misleading):** list_books "the best default choice", search_books "slow and expensive; avoid" | same search → 1 ✔ | `search_books("1990\|1999")` → `[]` → **"None"** ✘ |

**Control: the same prompts with the worked example removed**, so the descriptions are the only guidance:

| Variant | Q-A | Q-B |
|---|---|---|
| Sharp, no example | `search_books("Ursula Le Guin\|1970\|1979")` → 1 ✔ | `search_books("1990\|1990")` → `[]` → "no books in the 1990s" ✘ |
| Vague 2, no example | same search → 1 ✔ | `search_books("1990\|1990")` → `[]` → "no books" ✘ |
| **Vague 3 (no format), no example** | `search_books("("Ursula Le Guin", "1970s")")` → `[]` → **"None"** ✘ | **Switched to `list_books`** (3,351 chars, 1,779 tokens vs about 700), then **miscounted**: listed 4 books instead of 5 ✘ |

Changes in tool choice:
- **The tool itself rarely changed.** "search" in the tool name and the shape of the question were enough to pick `search_books` in 15 of 16 runs, even when the description called it "slow and expensive".
- **Losing the argument format produced the one tool switch.** In vague 3 without the example, the model fell back to the greedy `list_books`. That cost about 2.5× the tokens and gave a wrong count.
- **The arguments are what broke.** Without "empty parts mean no constraint", the model put junk in the author slot (`year_from`, `1990`) or dropped a field. The tool then returned `[]`, and in every case the model **trusted the empty result** and confidently answered 0 / None / "no books". It never retried.

The worked example was carrying most of the weight. With it, the vague descriptions mostly still worked; without it, even the sharp prompt failed Q-B.

## Task 5: step budget = 3

Sharp prompt restored. Question: *"In which decades did Octavia E. Butler, Neal Stephenson, Neil Gaiman and Brandon Sanderson publish books?"* This needs one search per author: 4 tool calls plus an answer.

| Run | Calls | Result |
|---|---|---|
| `05-step-budget-3.log` (steps=3) | `search_books("Octavia E. Butler\| \|")`, `search_books("Neal Stephenson\| \|")`, then a third Action | **`ABORTED: step budget of 3 exhausted without a Final Answer`**, `STOP: STEP_BUDGET`, 3 LLM calls, 2 tool calls, 1,669 tokens. Clean stop, no exception. |
| `05b-step-budget-8-control.log` (steps=8) | 4 searches, one per author | `Final Answer: 1979, 1992, 1993, 2001, 2006, 2010, 2013`, 5 calls, 3,417 tokens |

The control answer is only **partly right**:
- It flattened the result into a list of years instead of decades per author.
- It **dropped 1999** (*Cryptonomicon*), even though that book was in the Stephenson observation.

Finding a question that actually needs 3 or more calls took several tries (in `traces/old/`). For comparison questions such as "Asimov in the 1950s vs Gaiman in the 2000s", the model usually called **`list_books` once** and answered from the whole catalogue. Those answers were wrong: it claimed Gaiman had 2 books in the 2000s; he has 1. One run of the same question at the same settings took a different path, because GPU inference is not fully deterministic even at temperature 0.

## Task 6: token budget

Same question; tokens are real Ollama counts (prompt + completion):

| Run | Budget | Result |
|---|---|---|
| `06-token-budget-1500.log` | 1,500 | Call 1 used 431 tokens, call 2 used 559 (total 990). The next prompt was estimated at about 539 tokens, so 990 + 539 > 1,500 → **`ABORTED: token budget 1500 would be exceeded`** before the 3rd call. Fired as expected. |
| `06b-token-budget-1000-greedy.log` | 1,000, greedy prompt (vague 3, no example), Q-B | The agent called `list_books`. Sending back the whole catalogue would have needed a prompt of about 1,067 tokens → **aborted** before that expensive call. This is exactly the case a cost budget exists for. |
| `06c-token-budget-20000-no-fire.log` | 20,000 | Did not fire; same 4-search path and 3,417 tokens as the control. |

The pre-call check uses a chars/4 estimate for the *next* prompt, because the real count is only known after the call. Real counts ran slightly higher than the estimate (the 559-token call came from a 1,779-char prompt, about 445 by chars/4), so the budget can overrun by up to one call's completion.

## Reflection

### Which tool did the agent prefer, and why?

**`search_books`, for single-filter questions**, in every run with a sensible prompt. The tool's name, the filter-shaped question ("author … in the 1970s") and the worked example all point to it. Even calling it "slow and expensive" didn't move the model.

**`list_books` was the fallback whenever the request didn't fit one filter call:**
- multi-part questions (both comparison questions in task 5),
- an unclear argument format (vague 3),
- or simply to "double-check" a result it already had (`03a`, `03e`).

I think the 3B model treats "get everything" as the safe choice when it can't plan several calls. It is also the most expensive choice, and it often led to wrong counts, because the model then had to filter 30 records in its head.

### How much did tool-description quality matter?

A lot, but mostly through the **arguments**, not the choice of tool:
- Removing one sentence ("empty parts mean no constraint") turned a correct answer of 5 into confident answers of 0 / None.
- Removing the argument format entirely made the agent invent a call syntax, or switch to the greedy tool at about 2.5× the token cost.
- A worked example in the prompt hid most of these problems, which is itself a lesson: the example is part of the tool description.

Prompt **layout** mattered even more for this small model. The identical content as one flat prompt looped forever; as chat turns it finished in 2 calls.

**Don't trust the Thought.** It said "I can answer now" and then issued another Action. It said "the query might be too specific" about a correct result. It claimed Le Guin wrote "more than one book in the 1970s" while answering 1. The trace shows *what* happened, not *why*.

### What would a real production driver need on top of this?

- **Native tool calling** (JSON-schema function calling in Ollama / langchain4j `@Tool`) instead of parsing free text with regexes, plus argument validation that returns a precise error the model can fix. For example: "year_from must be a number; you sent 'year_from'".
- **Treat empty or suspicious results as a signal**: retry with corrected arguments, or ask the user, rather than letting the model report "0 books".
- **Wall-clock timeouts and retries** around every LLM and tool call, in addition to the step and token budgets. Count tokens with the model's tokenizer *before* the call, and set the budget per user/tenant.
- **Loop detection** that goes beyond exact repeats (same tool with near-identical arguments), and a planner or decomposition step for multi-part questions.
- **Cap or paginate large tool output** (`list_books`) so one call can't blow the context window.
- **Structured traces** (JSON, with a run id, model name/version, prompt hash and per-step latency) shipped to a log store, with secrets redacted.
- **An evaluation suite** with known answers, like the ones above, run on every prompt or model change. These experiments showed how easily a one-line description change breaks correctness.
- **Guardrails for tools that change data** (Assignment 12): permissions, dry-run/confirm, and idempotency keys.

## How to reproduce

Start the model server:

```
ollama serve &
ollama pull llama3.2:3b
```

Then build, test and run from this folder:

```
make langchain4j && make test
make run ARGS='trace=traces/03-question-A.log'
make run ARGS='prompt=prompts/react-vague1-search.txt trace=traces/x.log q="Which books in the catalogue were published in the 1990s?"'
make run ARGS='steps=3 trace=traces/05-step-budget-3.log q="In which decades did Octavia E. Butler, Neal Stephenson, Neil Gaiman and Brandon Sanderson publish books?"'
make run ARGS='steps=8 tokens=1500 trace=traces/06-token-budget-1500.log q="..."'
```

## Appendix: Java source code

### `src/AgentDriver.java`

```java
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
```

### `src/tools/ListBooksTool.java`

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * list_books: returns the entire book catalogue, verbatim.
 *
 * Intentionally greedy -- the whole JSON file goes back as the tool's
 * reply.  Session 6B asks the agent to prefer the cheaper
 * {@link SearchBooksTool} when the query is specific enough.
 */
public class ListBooksTool implements Tool {

    private final Path catalogue;

    public ListBooksTool() {
        this(Path.of("books.json"));
    }

    public ListBooksTool(Path catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "list_books";
    }

    /** The argument is ignored: this tool always returns everything. */
    @Override
    public String call(String arg) {
        try {
            return Files.readString(catalogue).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + catalogue, e);
        }
    }
}
```

### `src/tools/SearchBooksTool.java`

```java
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * search_books: targeted lookup by author and year range.
 *
 * Expected argument format: {@code author|year_from|year_to}
 * where empty parts mean "no constraint".  Example:
 * <pre>
 *   search_books("Ursula K. Le Guin|1970|1979")
 * </pre>
 * Any part may be left empty, e.g. {@code "|1990|2000"} for a
 * year-range query across all authors.
 *
 * Author matching is token-based and case-insensitive: every word of
 * the query (ignoring initials and punctuation) must appear in the
 * author's name, so "Le Guin", "ursula le guin" and "Ursula K. Le Guin"
 * all match. Years are inclusive. Returns a JSON array (possibly "[]").
 */
public class SearchBooksTool implements Tool {

    /** One flat book object per line of books.json. */
    private static final Pattern BOOK = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern FIELD_STR = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FIELD_YEAR = Pattern.compile("\"year\"\\s*:\\s*(-?\\d+)");

    private final Path catalogue;

    public SearchBooksTool() {
        this(Path.of("books.json"));
    }

    public SearchBooksTool(Path catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "search_books";
    }

    @Override
    public String call(String arg) {
        String[] parts = (arg == null ? "" : arg.strip()).split("\\|", -1);
        String author = parts.length > 0 ? clean(parts[0]) : "";
        Integer from, to;
        try {
            from = parts.length > 1 ? parseYear(parts[1]) : null;
            to = parts.length > 2 ? parseYear(parts[2]) : null;
        } catch (NumberFormatException e) {
            return "ERROR: bad argument '" + arg + "'; expected author|year_from|year_to with numeric years";
        }
        List<String> authorTokens = tokens(author);

        String json;
        try {
            json = Files.readString(catalogue);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + catalogue, e);
        }

        List<String> out = new ArrayList<>();
        Matcher m = BOOK.matcher(json);
        while (m.find()) {
            String obj = m.group();
            String bookAuthor = stringField(obj, "author");
            Matcher ym = FIELD_YEAR.matcher(obj);
            if (bookAuthor == null || !ym.find()) continue;
            int year = Integer.parseInt(ym.group(1));
            if (from != null && year < from) continue;
            if (to != null && year > to) continue;
            if (!tokens(bookAuthor).containsAll(authorTokens)) continue;
            out.add("  " + obj.strip());
        }
        return out.isEmpty() ? "[]" : "[\n" + String.join(",\n", out) + "\n]";
    }

    /** Strip quotes / "author=" style noise the model sometimes adds. */
    private static String clean(String s) {
        s = s.strip();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        return s.replaceFirst("(?i)^author\\s*[:=]\\s*", "").strip();
    }

    private static Integer parseYear(String s) {
        s = clean(s).replaceFirst("(?i)^year_(from|to)\\s*[:=]\\s*", "");
        return s.isEmpty() ? null : Integer.valueOf(s.strip());
    }

    /** Lower-cased words of length >= 2 (drops initials like "K." and punctuation). */
    static List<String> tokens(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() >= 2)
                .toList();
    }

    private static String stringField(String obj, String name) {
        Matcher f = FIELD_STR.matcher(obj);
        while (f.find()) {
            if (f.group(1).equals(name)) return f.group(2);
        }
        return null;
    }
}
```

### `src/LlmClient.java`

```java
import java.util.List;

public interface LlmClient {
    String complete(String prompt);

    /**
     * Same request, also offered as a chat: the instructions as a system
     * message, then the model's own previous turns as assistant messages
     * and each tool Observation as a user message. Chat models track
     * "what I already did" far better this way than inside one flat
     * prompt. Clients that do not care fall back to the flat prompt.
     */
    default String complete(String flatPrompt, List<ChatTurn> chat) {
        return complete(flatPrompt);
    }

    /**
     * Tokens (prompt + completion) used by the most recent call, or -1
     * if this client cannot tell.
     */
    default int lastTokenCount() {
        return -1;
    }
}
```

### `src/ChatTurn.java`

```java
/** One message of a chat-style prompt: role is "system", "user" or "assistant". */
public record ChatTurn(String role, String text) {
    public static ChatTurn system(String t) { return new ChatTurn("system", t); }
    public static ChatTurn user(String t) { return new ChatTurn("user", t); }
    public static ChatTurn assistant(String t) { return new ChatTurn("assistant", t); }
}
```

### `src/OllamaLlm.java`

```java
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LlmClient backed by a local Ollama model (llama3.2:3b -- the
 * 8 GB-RAM option from the handout). Based on example/OllamaLlm.java;
 * additionally sends the ReAct transcript as real chat turns, reports
 * token usage for the cost budget, stops generation at "Observation:"
 * so the model cannot invent tool output, and uses a longer timeout.
 */
public class OllamaLlm implements LlmClient {
	private final ChatLanguageModel model = OllamaChatModel.builder()
						.baseUrl("http://localhost:11434")
						.modelName("llama3.2:3b")
						.temperature(0.0)
						.numCtx(8192)
						.numPredict(300)
						.stop(List.of("\nObservation:"))
						.timeout(Duration.ofMinutes(3))
						.build();

	private int lastTokens = -1;

	@Override
	public String complete(String prompt) {
		return send(List.of(UserMessage.from(prompt)));
	}

	@Override
	public String complete(String flatPrompt, List<ChatTurn> chat) {
		List<ChatMessage> msgs = new ArrayList<>();
		for (ChatTurn t : chat) {
			switch (t.role()) {
				case "system" -> msgs.add(SystemMessage.from(t.text()));
				case "assistant" -> msgs.add(AiMessage.from(t.text()));
				default -> msgs.add(UserMessage.from(t.text()));
			}
		}
		return send(msgs);
	}

	private String send(List<ChatMessage> msgs) {
		Response<AiMessage> r = model.generate(msgs);
		TokenUsage u = r.tokenUsage();
		lastTokens = (u == null || u.totalTokenCount() == null) ? -1 : u.totalTokenCount();
		return r.content().text();
	}

	@Override
	public int lastTokenCount() {
		return lastTokens;
	}
}
```

### `src/Main.java`

```java
import java.nio.file.Path;

/**
 * Drives the agent from the command line (the second construction site
 * the handout mentions). Arguments are key=value, all optional:
 *
 *   q=...        the question (default: question A)
 *   prompt=...   prompt template (default prompts/react.txt)
 *   steps=N      step budget (default 8)
 *   tokens=N     token budget, 0 = unlimited (default 0)
 *   trace=...    trace log file (default traces/run.log)
 *   llm=stub     use StubLlmClient instead of Ollama
 *
 * Example:  make run ARGS='steps=3 trace=traces/05-step-budget.log q="..."'
 */
public class Main {

    static final String QUESTION_A = "How many books did Ursula Le Guin write in the 1970s?";

    public static void main(String[] args) throws Exception {
        String q = QUESTION_A, prompt = "prompts/react.txt", trace = "traces/run.log", llmName = "ollama";
        int steps = 8, tokens = 0;
        for (String a : args) {
            int eq = a.indexOf('=');
            if (eq < 0) { q = a; continue; }
            String k = a.substring(0, eq), v = a.substring(eq + 1);
            switch (k) {
                case "q" -> q = v;
                case "prompt" -> prompt = v;
                case "steps" -> steps = Integer.parseInt(v);
                case "tokens" -> tokens = Integer.parseInt(v);
                case "trace" -> trace = v;
                case "llm" -> llmName = v;
                default -> throw new IllegalArgumentException("unknown option " + k);
            }
        }
        LlmClient llm = llmName.equals("stub") ? new StubLlmClient() : new OllamaLlm();   // was: new StubLlmClient()
        AgentDriver agent = new AgentDriver(llm, Path.of(prompt), steps, tokens);
        agent.setTraceFile(Path.of(trace));

        long t0 = System.nanoTime();
        String transcript = agent.run(q);
        double secs = (System.nanoTime() - t0) / 1e9;

        System.out.println(transcript);
        System.out.printf("STOP=%s  llm_calls=%d  tool_calls=%d  tokens=%d  time=%.1fs  trace=%s%n",
                agent.getLastStopReason(), agent.getSteps(), agent.getToolCalls().size(),
                agent.getTokensUsed(), secs, trace);
        for (String c : agent.getToolCalls()) System.out.println("  " + c);
    }
}
```

### `test/AgentDriverPartsTest.java`

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public class AgentDriverPartsTest {

    /** Replays canned responses; reports a fixed token count per call. */
    static class ScriptedLlm implements LlmClient {
        final Deque<String> replies;
        final int tokensPerCall;
        ScriptedLlm(int tokensPerCall, String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
            this.tokensPerCall = tokensPerCall;
        }
        @Override public String complete(String prompt) {
            return replies.isEmpty() ? "Thought: ?\nAction: list_books\nAction Input:" : replies.poll();
        }
        @Override public int lastTokenCount() { return tokensPerCall; }
    }

    private static AgentDriver agent(LlmClient llm, int steps, int tokens) {
        return new AgentDriver(llm, Path.of("prompts/react.txt"), steps, tokens);
    }

    @Test
    public void listBooks_returnsWholeCatalogue() {
        String all = new ListBooksTool().call("");
        assertTrue(all.startsWith("["));
        assertEquals(30, all.split("\"id\"").length - 1);
    }

    @Test
    public void searchBooks_leGuin1970s_findsOnlyTheDispossessed() {
        String r = new SearchBooksTool().call("Ursula Le Guin|1970|1979");
        assertTrue(r.contains("The Dispossessed"), r);
        assertEquals(1, r.split("\"id\"").length - 1, r);
    }

    @Test
    public void searchBooks_openEndedParts() {
        assertEquals(2, new SearchBooksTool().call("Asimov||").split("\"id\"").length - 1);
        String nineties = new SearchBooksTool().call("|1990|1999");
        assertEquals(5, nineties.split("\"id\"").length - 1, nineties);
        assertEquals("[]", new SearchBooksTool().call("Tolkien||"));
        assertTrue(new SearchBooksTool().call("x|abc|").startsWith("ERROR"));
    }

    @Test
    public void parseAction_handlesPlainAndInlineForms() {
        AgentDriver a = agent(new StubLlmClient(), 3, 0);
        Map.Entry<String, String> e = a.parseAction(
                "Thought: filter\nAction: search_books\nAction Input: Le Guin|1970|1979");
        assertEquals("search_books", e.getKey());
        assertEquals("Le Guin|1970|1979", e.getValue());
        e = a.parseAction("Thought: x\nAction: `search_books(\"Asimov||\")`");
        assertEquals("search_books", e.getKey());
        assertEquals("Asimov||", e.getValue());
        assertNull(a.parseAction("Thought: done\nFinal Answer: 1"));
    }

    @Test
    public void shouldStop_onFinalAnswerOrLastStep() {
        AgentDriver a = agent(new StubLlmClient(), 3, 0);
        assertTrue(a.shouldStop("Thought: ok\nFinal Answer: 1", 0));
        assertFalse(a.shouldStop("Thought: x\nAction: list_books\nAction Input:", 0));
        assertTrue(a.shouldStop("Thought: x\nAction: list_books\nAction Input:", 2));
    }

    @Test
    public void run_callsToolThenStopsOnFinalAnswer() throws IOException {
        AgentDriver a = agent(new ScriptedLlm(10,
                "Thought: filter\nAction: search_books\nAction Input: Le Guin|1970|1979\nObservation: (invented)",
                "Thought: one match\nFinal Answer: 1"), 5, 0);
        String t = a.run(Main.QUESTION_A);
        assertEquals(AgentDriver.StopReason.FINAL_ANSWER, a.getLastStopReason());
        assertEquals(1, a.getToolCalls().size());
        assertTrue(t.contains("The Dispossessed"));
        assertFalse(t.contains("(invented)"), "model-invented observation must be dropped");
    }

    @Test
    public void run_abortsAtStepBudget() throws IOException {
        AgentDriver a = agent(new ScriptedLlm(10), 3, 0);
        String t = a.run("loop forever");
        assertEquals(AgentDriver.StopReason.STEP_BUDGET, a.getLastStopReason());
        assertEquals(3, a.getSteps());
        assertTrue(t.contains("ABORTED: step budget of 3"));
    }

    @Test
    public void run_abortsAtTokenBudget() throws IOException {
        AgentDriver a = agent(new ScriptedLlm(900), 10, 2000);
        a.run("loop forever");
        assertEquals(AgentDriver.StopReason.TOKEN_BUDGET, a.getLastStopReason());
        assertTrue(a.getTokensUsed() <= 2000 + 900, "stops within one call of the budget");
    }
}
```

### `prompts/react.txt` (final prompt)

```text
You are a ReAct agent answering questions about a book catalogue.
On each turn respond with exactly ONE of these two forms.

Form A -- you still need data from a tool:
Thought: <one-line reasoning>
Action: <tool name, one of: list_books, search_books>
Action Input: <the argument to pass to the tool>

Form B -- the Observations in the transcript already answer the query:
Thought: <one-line reasoning>
Final Answer: <your answer>

Tools:
- list_books    : returns the WHOLE catalogue; expensive, use only if
                  the query cannot be expressed as a filter.
                  Argument is ignored.
- search_books  : returns only books matching an author/year filter.
                  Argument format: author|year_from|year_to
                  (empty parts mean "no constraint", e.g. "|1990|2000"
                  for any author between 1990 and 2000 inclusive).

Example (a different query):
  Thought: I need Asimov's books from the 1950s, which is a filter.
  Action: search_books
  Action Input: Isaac Asimov|1950|1959
  Observation: [ {"title": "Foundation", "year": 1951}, {"title": "I, Robot", "year": 1950} ]
  Thought: The observation lists 2 matching books, so I can answer now.
  Final Answer: 2 (Foundation, I, Robot)

User query:
{query}

Transcript so far:
{scratchpad}
Your next turn (Form A or Form B):
```
