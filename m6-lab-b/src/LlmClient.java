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
