package no.cantara.llmweather.sources;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves recorded responses from a directory, keyed by host: a request to https://status.openai.com/api/v2/...
 * is answered with {@code <dir>/status.openai.com.json}, and the Instatus path /summary.json with
 * {@code <dir>/<host>.instatus.json}. A missing file behaves like a network failure.
 */
public final class FixtureFetcher implements HttpFetcher {
    private final Path dir;

    public FixtureFetcher(Path dir) {
        this.dir = dir;
    }

    @Override
    public String get(String url) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        // Instatus pages are recorded as <host>.instatus.json so one host can have one format or the other.
        Path file = dir.resolve(host + ("/summary.json".equals(uri.getPath()) ? ".instatus.json" : ".json"));
        if (!Files.exists(file)) {
            throw new IOException("No fixture " + file.getFileName() + " for " + url);
        }
        return Files.readString(file);
    }
}
