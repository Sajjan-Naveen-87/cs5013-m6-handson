/** One message of a chat-style prompt: role is "system", "user" or "assistant". */
public record ChatTurn(String role, String text) {
    public static ChatTurn system(String t) { return new ChatTurn("system", t); }
    public static ChatTurn user(String t) { return new ChatTurn("user", t); }
    public static ChatTurn assistant(String t) { return new ChatTurn("assistant", t); }
}
