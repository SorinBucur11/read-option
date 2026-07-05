package app.readoption.agent;

/**
 * The advice loop hit its tool-iteration cap while the model was still requesting
 * tools. Thrown loud (500) — a partial answer silently returned as advice is the
 * failure mode this cap exists to prevent.
 */
public class AgentLoopLimitException extends RuntimeException {

    public AgentLoopLimitException(long sessionId, int maxIterations) {
        super("draft advice for session " + sessionId + " exceeded " + maxIterations
                + " tool iterations without reaching a final answer");
    }
}
