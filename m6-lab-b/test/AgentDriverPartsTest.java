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
