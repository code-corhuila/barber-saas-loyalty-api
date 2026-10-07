package co.edu.corhuila.barbersaas.loyalty.domain.model;

import co.edu.corhuila.barbersaas.loyalty.domain.model.DomainException.InvalidValue;

/** The length rules of the text columns (06-data/models.md §8), checked before the database does. */
final class Text {

    private Text() {
    }

    /** A required text: trimmed, between 1 and {@code max} characters. */
    static String required(String field, String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > max) {
            throw new InvalidValue(field + " must have between 1 and " + max + " characters");
        }
        return text;
    }

    /** An optional text: blank becomes null; otherwise at most {@code max} characters. */
    static String optional(String field, String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new InvalidValue(field + " has at most " + max + " characters");
        }
        return text;
    }
}
