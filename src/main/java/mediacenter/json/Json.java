package mediacenter.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonBoolean;
import mediacenter.json.JsonValue.JsonNull;
import mediacenter.json.JsonValue.JsonNumber;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * Small, self-contained JSON reader/writer.
 *
 * <p>Deliberately strict enough to catch a corrupted settings file and lenient
 * enough to stay tiny; it is not a general-purpose JSON library.
 */
public final class Json {

    private Json() {
    }

    /** Parses a complete JSON document. */
    public static JsonValue parse(String text) throws JsonException {
        Parser parser = new Parser(text);
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    /** Parses a document that is required to be a JSON object. */
    public static JsonObject parseObject(String text) throws JsonException {
        JsonValue value = parse(text);
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new JsonException("Expected a JSON object at the top level");
    }

    /** Serializes a value using two-space indentation so the files stay hand-editable. */
    public static String write(JsonValue value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 0);
        out.append('\n');
        return out.toString();
    }

    // -- writing ------------------------------------------------------------

    private static void writeValue(JsonValue value, StringBuilder out, int indent) {
        switch (value) {
            case JsonObject(Map<String, JsonValue> members) -> writeObject(members, out, indent);
            case JsonArray(List<JsonValue> elements) -> writeArray(elements, out, indent);
            case JsonString(String text) -> writeString(text, out);
            case JsonNumber(double number) -> writeNumber(number, out);
            case JsonBoolean(boolean flag) -> out.append(flag);
            case JsonNull ignored -> out.append("null");
        }
    }

    private static void writeObject(Map<String, JsonValue> members, StringBuilder out, int indent) {
        if (members.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        int remaining = members.size();
        for (Map.Entry<String, JsonValue> member : members.entrySet()) {
            indent(out, indent + 1);
            writeString(member.getKey(), out);
            out.append(": ");
            writeValue(member.getValue(), out, indent + 1);
            if (--remaining > 0) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, indent);
        out.append('}');
    }

    private static void writeArray(List<JsonValue> elements, StringBuilder out, int indent) {
        if (elements.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < elements.size(); i++) {
            indent(out, indent + 1);
            writeValue(elements.get(i), out, indent + 1);
            if (i < elements.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, indent);
        out.append(']');
    }

    private static void writeNumber(double number, StringBuilder out) {
        if (number == Math.rint(number) && !Double.isInfinite(number) && Math.abs(number) < 1e15) {
            out.append((long) number);
        } else {
            out.append(number);
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }

    // -- parsing ------------------------------------------------------------

    private static final class Parser {

        private final String text;
        private int position;

        private Parser(String text) {
            this.text = text;
        }

        private JsonValue parseValue() throws JsonException {
            skipWhitespace();
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonString(parseString());
                case 't' -> parseLiteral("true", new JsonBoolean(true));
                case 'f' -> parseLiteral("false", new JsonBoolean(false));
                case 'n' -> parseLiteral("null", JsonNull.INSTANCE);
                default -> parseNumber();
            };
        }

        private JsonValue parseObject() throws JsonException {
            expect('{');
            Map<String, JsonValue> members = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return new JsonObject(members);
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                members.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    position++;
                } else if (c == '}') {
                    position++;
                    return new JsonObject(members);
                } else {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private JsonValue parseArray() throws JsonException {
            expect('[');
            List<JsonValue> elements = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return new JsonArray(elements);
            }
            while (true) {
                elements.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    position++;
                } else if (c == ']') {
                    position++;
                    return new JsonArray(elements);
                } else {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private String parseString() throws JsonException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("Unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("Unterminated escape sequence");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("Truncated unicode escape");
                        }
                        String hex = text.substring(position, position + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid unicode escape '\\u" + hex + "'");
                        }
                        position += 4;
                    }
                    default -> throw error("Invalid escape '\\" + escape + "'");
                }
            }
        }

        private JsonValue parseNumber() throws JsonException {
            int start = position;
            if (!atEnd() && (peek() == '-' || peek() == '+')) {
                position++;
            }
            while (!atEnd() && isNumberCharacter(text.charAt(position))) {
                position++;
            }
            String literal = text.substring(start, position);
            try {
                return new JsonNumber(Double.parseDouble(literal));
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number '" + literal + "' at offset " + start);
            }
        }

        private static boolean isNumberCharacter(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private JsonValue parseLiteral(String literal, JsonValue value) throws JsonException {
            if (!text.startsWith(literal, position)) {
                throw error("Expected '" + literal + "'");
            }
            position += literal.length();
            return value;
        }

        private void expect(char expected) throws JsonException {
            if (atEnd() || text.charAt(position) != expected) {
                throw error("Expected '" + expected + "'");
            }
            position++;
        }

        private char peek() throws JsonException {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(position);
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean atEnd() {
            return position >= text.length();
        }

        private JsonException error(String message) {
            return new JsonException(message + " at offset " + position);
        }
    }
}
