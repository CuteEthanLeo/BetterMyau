package myau.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * The chat screen's history. Each turn keeps what the player sees ({@link Message#display}) apart
 * from what the model was sent ({@link Message#content}: the situation block with the question).
 * Only the last few messages go back to the model, to stay within its context.
 */
public final class Conversation {
    public static final int WAITING = 0, STREAMING = 1, DONE = 2, ERROR = 3;
    /** Messages sent back to the model as context (the new question included). */
    private static final int CONTEXT_MESSAGES = 5;
    private static final int MAX_KEPT = 60;

    public static final class Message {
        public final boolean fromPlayer;
        public final String display;
        public final String content;
        public volatile String text;
        public volatile int state;
        /** "E3 = name" when the answer picked a target, shown under it. */
        public volatile String target;

        Message(boolean fromPlayer, String display, String content, String text, int state) {
            this.fromPlayer = fromPlayer;
            this.display = display;
            this.content = content;
            this.text = text;
            this.state = state;
        }
    }

    private final List<Message> messages = new ArrayList<>();

    public synchronized List<Message> messages() {
        return new ArrayList<>(this.messages);
    }

    public synchronized void clear() {
        this.messages.clear();
    }

    /** Adds the question and an empty answer; returns the answer to stream into. */
    public synchronized Message add(String display, String content) {
        this.messages.add(new Message(true, display, content, display, DONE));
        Message answer = new Message(false, "", "", "", WAITING);
        this.messages.add(answer);
        while (this.messages.size() > MAX_KEPT) this.messages.remove(0);
        return answer;
    }

    /** role/content pairs for the model: recent finished turns plus the new question. */
    public synchronized List<String[]> context() {
        List<String[]> out = new ArrayList<>();
        int end = this.messages.size() - 1; // the empty answer being written
        for (int i = Math.max(0, end - CONTEXT_MESSAGES); i < end; i++) {
            Message m = this.messages.get(i);
            if (!m.fromPlayer && m.state != DONE) continue;
            out.add(new String[]{m.fromPlayer ? "user" : "assistant", m.fromPlayer ? m.content : m.text});
        }
        // the context must start with a question
        while (!out.isEmpty() && !"user".equals(out.get(0)[0])) out.remove(0);
        return out;
    }
}
