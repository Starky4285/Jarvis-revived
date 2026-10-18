package com.jarvis.core.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A chat prompt as plain messages (system / user / assistant), independent of
 * any model's chat format. The app turns it into the loaded model's own
 * template (Phi-3, Qwen/ChatML, Gemma, Llama 3 …) through llama.cpp, so the
 * core never hard-codes one model family.
 *
 * Every message is sanitized on the way in: chat-template control tokens are
 * removed so user text, memory or e-mail content can never close its own block
 * and pose as the system.
 */
public final class ChatPrompt {

    public enum Role { SYSTEM, USER, ASSISTANT }

    public static final class Message {
        public final Role role;
        public final String content;

        Message(Role role, String content) {
            this.role = role;
            this.content = content;
        }

        @Override
        public String toString() {
            return role + ": " + content;
        }
    }

    private final List<Message> messages;
    private final boolean addAssistantStart;

    private ChatPrompt(List<Message> messages, boolean addAssistantStart) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.addAssistantStart = addAssistantStart;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Message> messages() {
        return messages;
    }

    /** True for normal prompts: the formatted text ends where the assistant's reply begins. */
    public boolean addAssistantStart() {
        return addAssistantStart;
    }

    /**
     * Phi-3 format. Used when the loaded model carries no chat template llama.cpp
     * understands, and by the JVM tests.
     */
    public String toPhi3() {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            switch (m.role) {
                case SYSTEM: sb.append("<|system|>\n"); break;
                case USER: sb.append("<|user|>\n"); break;
                default: sb.append("<|assistant|>\n");
            }
            sb.append(m.content).append("<|end|>\n");
        }
        if (addAssistantStart) sb.append("<|assistant|>\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return toPhi3();
    }

    /**
     * Removes control tokens of the chat formats llama.cpp can apply, plus stray
     * control characters. Not a general "prompt injection filter": it only makes
     * sure text stays inside the message it was put in.
     */
    public static String sanitize(String text) {
        if (text == null) return "";
        String t = text
                .replaceAll("<\\|[^>]{0,120}\\|>", " ")                       // Phi-3, ChatML, Llama 3
                .replaceAll("(?i)</?(?:start_of_turn|end_of_turn|bos|eos|s)>", " ") // Gemma, Llama 2
                .replaceAll("(?i)\\[/?INST\\]|<<SYS>>|<</SYS>>", " ")           // Mistral / Llama 2
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return t.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").trim();
    }

    public static final class Builder {
        private final List<Message> list = new ArrayList<>();
        private boolean addAssistantStart = true;

        public Builder system(String text) {
            return add(Role.SYSTEM, text);
        }

        public Builder user(String text) {
            return add(Role.USER, text);
        }

        public Builder assistant(String text) {
            return add(Role.ASSISTANT, text);
        }

        /** For a prefix that is only decoded into the cache (warm-up), not answered. */
        public Builder withoutAssistantStart() {
            addAssistantStart = false;
            return this;
        }

        private Builder add(Role role, String text) {
            list.add(new Message(role, sanitize(text)));
            return this;
        }

        public ChatPrompt build() {
            return new ChatPrompt(list, addAssistantStart);
        }
    }
}
