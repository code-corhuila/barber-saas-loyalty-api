package co.edu.corhuila.barbersaas.loyalty.adapter.in.http;

import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.loyalty.adapter.in.http.ApiError.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads a request body against its contract schema: every schema here is additionalProperties: false,
 * so an unknown field answers 400 instead of being ignored — which is how a product edit that carries
 * barbershopId is refused. Business rules stay in the domain.
 */
final class JsonBody {

    private final JsonNode node;
    private final List<FieldError> errors = new ArrayList<>();

    private JsonBody(JsonNode node) {
        this.node = node;
    }

    static JsonBody of(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new ValidationException("the body must be a JSON object", List.of());
        }
        JsonBody body = new JsonBody(node);
        node.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) {
                body.errors.add(new FieldError(f, "not allowed"));
            }
        });
        return body;
    }

    /** A required string of at most {@code max} characters; blank is left to the domain, which knows the rule. */
    String text(String field, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().length() > max) {
            errors.add(new FieldError(field, v == null ? "required" : "a string of at most " + max + " characters"));
            return null;
        }
        return v.asText();
    }

    String optionalText(String field, int max) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : text(field, max);
    }

    UUID optionalUuid(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(v.asText());
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, "must be a UUID"));
            return null;
        }
    }

    <E extends Enum<E>> E enumValue(String field, Class<E> type) {
        JsonNode v = node.get(field);
        try {
            return Enum.valueOf(type, v.asText());
        } catch (RuntimeException e) {
            errors.add(new FieldError(field, v == null ? "required" : "not an accepted value"));
            return null;
        }
    }

    UUID uuid(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            errors.add(new FieldError(field, "required"));
            return null;
        }
        return optionalUuid(field);
    }

    /** An integer of at least {@code min}; 0 when invalid, with the error collected. */
    int integer(String field, int min) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min) {
            errors.add(new FieldError(field, v == null ? "required" : "must be an integer of at least " + min));
            return 0;
        }
        return v.asInt();
    }

    /** An optional boolean with its default. */
    boolean bool(String field, boolean absent) {
        JsonNode v = node.get(field);
        if (v == null) {
            return absent;
        }
        if (!v.isBoolean()) {
            errors.add(new FieldError(field, "must be true or false"));
            return absent;
        }
        return v.asBoolean();
    }

    /** Throws every collected error at once, as one 400. */
    void validate() {
        if (!errors.isEmpty()) {
            throw new ValidationException("the request is not valid", errors);
        }
    }

    static <E extends Enum<E>> E parseEnum(String field, String value, Class<E> type) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "not an accepted value");
        }
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException("the request is not valid", List.of(new FieldError(field, message)));
    }
}
