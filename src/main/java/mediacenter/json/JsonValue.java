package mediacenter.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal immutable JSON tree.
 *
 * <p>The application stores a handful of small configuration files, so a tiny
 * hand-written model is preferred over pulling in a JSON library that would
 * have to be kept {@code jlink}-friendly.
 */
public sealed interface JsonValue {

    /** JSON object with insertion-ordered members. */
    record JsonObject(Map<String, JsonValue> members) implements JsonValue {

        public JsonObject {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        public static JsonObject of(Map<String, JsonValue> members) {
            return new JsonObject(members);
        }

        public Optional<JsonValue> get(String key) {
            return Optional.ofNullable(members.get(key));
        }

        public Optional<String> string(String key) {
            return get(key).flatMap(value -> value instanceof JsonString(String text)
                    ? Optional.of(text)
                    : Optional.empty());
        }

        /** Returns the value only when it is a non-blank string. */
        public Optional<String> nonBlankString(String key) {
            return string(key).map(String::trim).filter(text -> !text.isEmpty());
        }

        public Optional<Long> longValue(String key) {
            return get(key).flatMap(value -> switch (value) {
                case JsonNumber(double number) -> Optional.of((long) number);
                case JsonString(String text) -> {
                    try {
                        yield Optional.of(Long.parseLong(text.trim()));
                    } catch (NumberFormatException e) {
                        yield Optional.empty();
                    }
                }
                default -> Optional.empty();
            });
        }

        public boolean booleanValue(String key, boolean fallback) {
            return get(key)
                    .flatMap(value -> value instanceof JsonBoolean(boolean flag)
                            ? Optional.of(flag)
                            : Optional.<Boolean>empty())
                    .orElse(fallback);
        }

        /** Returns the object members of an array, silently skipping anything else. */
        public List<JsonObject> objectArray(String key) {
            return get(key)
                    .flatMap(value -> value instanceof JsonArray(List<JsonValue> elements)
                            ? Optional.of(elements)
                            : Optional.<List<JsonValue>>empty())
                    .map(elements -> {
                        List<JsonObject> objects = new ArrayList<>();
                        for (JsonValue element : elements) {
                            if (element instanceof JsonObject object) {
                                objects.add(object);
                            }
                        }
                        return List.copyOf(objects);
                    })
                    .orElseGet(List::of);
        }
    }

    /** JSON array. */
    record JsonArray(List<JsonValue> elements) implements JsonValue {

        public JsonArray {
            elements = List.copyOf(elements);
        }

        public static JsonArray of(List<JsonValue> elements) {
            return new JsonArray(elements);
        }
    }

    /** JSON string. */
    record JsonString(String value) implements JsonValue {

        public JsonString {
            if (value == null) {
                throw new IllegalArgumentException("JSON string value must not be null");
            }
        }
    }

    /** JSON number. Everything this application stores fits comfortably in a double. */
    record JsonNumber(double value) implements JsonValue {
    }

    /** JSON boolean. */
    record JsonBoolean(boolean value) implements JsonValue {
    }

    /** JSON null. */
    record JsonNull() implements JsonValue {

        public static final JsonNull INSTANCE = new JsonNull();
    }
}
