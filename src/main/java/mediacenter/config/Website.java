package mediacenter.config;

import java.util.UUID;

/**
 * One configured website tile: a name for the sofa and an address for the
 * browser. The media center never fetches the address itself — it is handed,
 * verbatim, to the browser the viewer configured.
 */
public record Website(String id, String name, String url) {

    public Website {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Website id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Website name must not be blank");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Website url must not be blank");
        }
    }

    /**
     * Creates a tile with a fresh identifier, defaulting the scheme: from ten
     * feet away nobody types {@code https://}, and a bare host is unambiguous.
     */
    public static Website create(String name, String url) {
        return new Website(UUID.randomUUID().toString(), name.trim(), withScheme(url.trim()));
    }

    public Website withName(String newName) {
        return new Website(id, newName.trim(), url);
    }

    public Website withUrl(String newUrl) {
        return new Website(id, name, withScheme(newUrl.trim()));
    }

    /** The host alone — what a tile shows under the name without shouting the URL. */
    public String host() {
        String rest = url.contains("://") ? url.substring(url.indexOf("://") + 3) : url;
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String withScheme(String address) {
        return address.contains("://") ? address : "https://" + address;
    }
}
