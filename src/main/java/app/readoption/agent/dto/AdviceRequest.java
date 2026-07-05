package app.readoption.agent.dto;

/**
 * One user turn for the draft advisor. Blank-message rejection is a service
 * check ({@code InvalidDraftRequestException} → 400), not a bean annotation, so
 * the failure shape matches the rest of the draft API.
 */
public record AdviceRequest(
        String message
) {
}
