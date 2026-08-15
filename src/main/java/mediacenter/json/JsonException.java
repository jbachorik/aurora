package mediacenter.json;

/** Thrown when a JSON document cannot be parsed. */
public class JsonException extends Exception {

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
