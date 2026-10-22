import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.time.Duration;

/**
 * LlmClient backed by a local Ollama model (llama3.2:3b -- the
 * 8 GB-RAM option from the handout). Based on example/OllamaLlm.java,
 * with a longer timeout and an output cap so a rambling answer cannot
 * hit langchain4j's default 60 s timeout.
 */
public class OllamaLlm implements LlmClient {
	private final ChatLanguageModel model = OllamaChatModel.builder()
						.baseUrl("http://localhost:11434")
						.modelName("llama3.2:3b")
						.temperature(0.0)
						.numPredict(400)
						.timeout(Duration.ofMinutes(3))
						.build();
	@Override
	public String complete(String prompt) {
		return model.generate(prompt);
	}
}
