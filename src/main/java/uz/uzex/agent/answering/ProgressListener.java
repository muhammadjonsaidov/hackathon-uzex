package uz.uzex.agent.answering;

/** Lets the page show what the agent is doing while the judge waits. */
@FunctionalInterface
public interface ProgressListener {

    void step(String label);

    /** A query or lookup that just finished. Its rows are real data, so they can be shown right away. */
    default void evidence(AgentResponse.Evidence evidence) {}

    ProgressListener NONE = label -> {};
}
