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
