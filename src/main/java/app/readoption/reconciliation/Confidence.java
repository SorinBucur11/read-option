package app.readoption.reconciliation;

/**
 * The model's confidence in its verdict, as an enum — never a double. A double is a
 * fake number that implies a calibration the model does not have and tempts
 * arithmetic on model vibes. An enum keeps the model in classification mode.
 */
public enum Confidence {
    LOW, MEDIUM, HIGH
}
