package com.vestigium.vestigiumnpc.dialogue;

import java.util.*;

/**
 * Immutable directed graph of dialogue nodes keyed by string IDs.
 * Build with {@link DialogueTree#builder(String)}.
 */
public final class DialogueTree {

    private final String rootKey;
    private final Map<String, DialogueNode> nodes;

    private DialogueTree(String rootKey, Map<String, DialogueNode> nodes) {
        this.rootKey = rootKey;
        this.nodes   = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    public String rootKey()              { return rootKey; }
    public DialogueNode node(String key) { return nodes.get(key); }

    public static Builder builder(String rootKey) { return new Builder(rootKey); }

    public static final class Builder {
        private final String rootKey;
        private final Map<String, DialogueNode> nodes = new LinkedHashMap<>();

        private Builder(String rootKey) { this.rootKey = rootKey; }

        public Builder node(String key, DialogueNode node) {
            nodes.put(key, node);
            return this;
        }

        /** Static lines advancing to nextKey, or ending if nextKey is null. */
        public Builder message(String key, String nextKey, String... lines) {
            return node(key, (p, v) -> new DialogueStep(List.of(lines), nextKey));
        }

        public DialogueTree build() {
            if (!nodes.containsKey(rootKey))
                throw new IllegalStateException("Root key '" + rootKey + "' not registered.");
            return new DialogueTree(rootKey, nodes);
        }
    }
}
