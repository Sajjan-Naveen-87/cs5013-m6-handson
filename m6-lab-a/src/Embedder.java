public interface Embedder {
    /** Return a fixed-length embedding for the given text. */
    float[] embed(String text);

    /**
     * Name + version of the embedding model. Recorded alongside the index so
     * a query embedded with a different model fails loudly instead of
     * producing meaningless similarity scores.
     */
    default String modelId() {
        return getClass().getSimpleName();
    }
}
