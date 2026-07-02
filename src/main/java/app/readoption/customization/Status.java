package app.readoption.customization;

/**
 * Loop-control status of a parse/refine turn: any BLOCKING issue means the config
 * cannot proceed and the user must supply input ({@code NEEDS_INPUT}); otherwise the
 * config may be confirmed ({@code READY}). {@code READY} is <b>not</b> committed —
 * nothing persists until the confirm gate.
 */
public enum Status {
    NEEDS_INPUT,
    READY
}
