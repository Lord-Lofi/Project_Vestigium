package com.vestigium.vestigiumnpc.dialogue;

import java.util.List;

/**
 * A single step in a dialogue sequence.
 * {@code nextKey} is {@code null} when the step ends the conversation.
 */
public record DialogueStep(List<String> lines, String nextKey) {
    public boolean isTerminal() { return nextKey == null; }
}
