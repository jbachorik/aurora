package mediacenter.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonBoolean;
import mediacenter.json.JsonValue.JsonNumber;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

class JsonTest {

    @Test
    void parsesTheConfigurationExampleFromTheSpecification() throws JsonException {
        String text = """
                {
                  "vlcPath": "C:\\\\Program Files\\\\VideoLAN\\\\VLC\\\\vlc.exe",
                  "mediaRoots": [
                    {
                      "name": "Movies",
                      "path": "\\\\\\\\synology\\\\video\\\\Movies",
                      "type": "MOVIES"
                    }
                  ]
                }
                """;

        JsonObject document = Json.parseObject(text);

        assertEquals(Optional.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"), document.string("vlcPath"));
        List<JsonObject> roots = document.objectArray("mediaRoots");
        assertEquals(1, roots.size());
        assertEquals(Optional.of("\\\\synology\\video\\Movies"), roots.getFirst().string("path"));
    }

    @Test
    @DisplayName("Windows paths survive a write/parse round trip")
    void escapesBackslashes() throws JsonException {
        String path = "\\\\synology\\video\\Movies";
        String written = Json.write(new JsonObject(Map.of("path", new JsonString(path))));

        assertTrue(written.contains("\\\\\\\\synology"));
        assertEquals(Optional.of(path), Json.parseObject(written).string("path"));
    }

    @Test
    void readsAllValueKinds() throws JsonException {
        JsonObject document = Json.parseObject("""
                {"text":"a","number":42,"fraction":1.5,"yes":true,"no":false,"nothing":null,"list":[1,2]}
                """);

        assertEquals(Optional.of("a"), document.string("text"));
        assertEquals(Optional.of(42L), document.longValue("number"));
        assertTrue(document.booleanValue("yes", false));
        assertFalse(document.booleanValue("no", true));
        assertTrue(document.get("nothing").orElseThrow() instanceof JsonValue.JsonNull);
        assertEquals(2, ((JsonArray) document.get("list").orElseThrow()).elements().size());
    }

    @Test
    void handlesEscapeSequences() throws JsonException {
        JsonObject document = Json.parseObject("{\"text\":\"line\\nbreak\\ttab \\u00e9 \\\"quoted\\\"\"}");

        assertEquals(Optional.of("line\nbreak\ttab é \"quoted\""), document.string("text"));
    }

    @Test
    void writesIntegersWithoutADecimalPoint() {
        assertTrue(Json.write(new JsonNumber(42)).contains("42"));
        assertFalse(Json.write(new JsonNumber(42)).contains("42.0"));
    }

    @Test
    void writesEmptyContainersCompactly() {
        assertEquals("{}\n", Json.write(new JsonObject(Map.of())));
        assertEquals("[]\n", Json.write(new JsonArray(List.of())));
    }

    @Test
    void roundTripsNestedStructures() throws JsonException {
        JsonObject original = new JsonObject(new java.util.LinkedHashMap<>(Map.of(
                "flag", new JsonBoolean(true),
                "items", new JsonArray(List.of(new JsonString("one"), new JsonNumber(2))))));

        JsonObject reparsed = Json.parseObject(Json.write(original));

        assertTrue(reparsed.booleanValue("flag", false));
        assertEquals(2, ((JsonArray) reparsed.get("items").orElseThrow()).elements().size());
    }

    @Test
    void rejectsBrokenDocuments() {
        assertThrows(JsonException.class, () -> Json.parse("{"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\" 1}"));
        assertThrows(JsonException.class, () -> Json.parse("{} trailing"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(JsonException.class, () -> Json.parseObject("[1,2]"));
    }

    @Test
    void missingOrWronglyTypedMembersDegradeGracefully() throws JsonException {
        JsonObject document = Json.parseObject("{\"name\":\"  \",\"number\":\"nope\",\"list\":5}");

        assertEquals(Optional.empty(), document.nonBlankString("name"));
        assertEquals(Optional.empty(), document.longValue("number"));
        assertEquals(Optional.empty(), document.string("missing"));
        assertTrue(document.objectArray("list").isEmpty());
        assertTrue(document.booleanValue("missing", true));
    }
}
