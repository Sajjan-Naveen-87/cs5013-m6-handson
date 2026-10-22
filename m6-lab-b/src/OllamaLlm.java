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
