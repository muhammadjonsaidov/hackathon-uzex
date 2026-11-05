package uz.uzex.agent.answering;

/** Lets the page show what the agent is doing while the judge waits. */
@FunctionalInterface
public interface ProgressListener {

    void step(String label);

    ProgressListener NONE = label -> {};
}
