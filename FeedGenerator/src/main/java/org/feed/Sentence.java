package org.feed;

/** Sentence identity preserves duplicate text at different positions as distinct map keys. */
public record Sentence(long number) {
    public Sentence {
        if (number < 1) throw new IllegalArgumentException("Sentence number must be positive");
    }
}
