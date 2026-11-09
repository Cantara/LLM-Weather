package no.cantara.llmweather.sources;

import com.fasterxml.jackson.databind.JsonNode;
import no.cantara.llmweather.Json;
import no.cantara.llmweather.model.Incident;
import no.cantara.llmweather.model.Maintenance;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a provider's public status page. Most LLM providers use Atlassian Statuspage (v2 summary API);
 * some use Instatus, which serves a different JSON at /summary.json. Both are normalised to one Summary.
 */
public final class StatusPageClient {

    /** indicator is none, minor, major, critical or maintenance. */
    public record Summary(
            String indicator,
            String description,
            List<Incident> incidents,
            List<Maintenance> maintenances,
            Instant updatedAt) {

        public long activeIncidents() {
            return incidents.stream().filter(Incident::isActive).count();
        }
    }

    private final HttpFetcher fetcher;

    public StatusPageClient(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Summary fetch(String baseUrl) throws IOException {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        IOException statuspageFailure;
        try {
            return parse(fetcher.get(base + "/api/v2/summary.json"));
        } catch (IOException e) {
            statuspageFailure = e;
        }
        try {
            String json = fetcher.get(base + "/summary.json");
            JsonNode root = Json.mapper().readTree(json);
            if (root.has("activeIncidents") || root.path("page").has("status")) {
                return parseInstatus(root);
            }
        } catch (IOException ignored) {
            // fall through and report the first failure
        }
        throw statuspageFailure;
    }

    /** Instatus summary: page.status is UP, HASISSUES or UNDERMAINTENANCE; incidents carry impact and status in caps. */
    static Summary parseInstatus(JsonNode root) {
        String pageStatus = root.path("page").path("status").asText("UP").toUpperCase();
        List<Incident> incidents = new ArrayList<>();
        String worst = "none";
        for (JsonNode n : root.path("activeIncidents")) {
            String impact = switch (n.path("impact").asText("").toUpperCase()) {
                case "MAJOROUTAGE" -> "critical";
                case "PARTIALOUTAGE" -> "major";
                case "DEGRADEDPERFORMANCE" -> "minor";
                default -> "none";
            };
            if (rank(impact) > rank(worst)) worst = impact;
            incidents.add(new Incident(
                    n.path("id").asText(null),
                    n.path("name").asText(""),
                    n.path("status").asText("investigating").toLowerCase(),
                    impact,
                    instant(n.path("started")),
                    instant(n.path("updatedAt")),
                    n.path("url").asText(null),
                    null));
        }
        List<Maintenance> maintenances = new ArrayList<>();
        for (JsonNode n : root.path("activeMaintenances")) {
            maintenances.add(new Maintenance(
                    n.path("id").asText(null),
                    n.path("name").asText(""),
                    n.path("status").asText(null) == null ? null : n.path("status").asText().toLowerCase(),
                    instant(n.path("start")),
                    null,
                    n.path("url").asText(null)));
        }
        String indicator;
        String description;
        if ("UNDERMAINTENANCE".equals(pageStatus)) {
            indicator = "maintenance";
            description = "Under maintenance";
        } else if (!incidents.isEmpty()) {
            indicator = "none".equals(worst) ? "minor" : worst;
            description = "Experiencing issues";
        } else if ("HASISSUES".equals(pageStatus)) {
            indicator = "minor";
            description = "Experiencing issues";
        } else {
            indicator = "none";
            description = "All Systems Operational";
        }
        return new Summary(indicator, description, incidents, maintenances, null);
    }

    private static int rank(String impact) {
        return switch (impact) {
            case "critical" -> 3;
            case "major" -> 2;
            case "minor" -> 1;
            default -> 0;
        };
    }

    public static Summary parse(String json) throws IOException {
        JsonNode root = Json.mapper().readTree(json);
        JsonNode status = root.path("status");
        String indicator = status.path("indicator").asText("none");
        String description = status.path("description").asText("");
        Instant updatedAt = instant(root.path("page").path("updated_at"));

        List<Incident> incidents = new ArrayList<>();
        for (JsonNode n : root.path("incidents")) {
            String latest = null;
            JsonNode updates = n.path("incident_updates");
            if (updates.isArray() && updates.size() > 0) {
                latest = updates.get(0).path("body").asText(null);
            }
            incidents.add(new Incident(
                    n.path("id").asText(null),
                    n.path("name").asText(""),
                    n.path("status").asText(null),
                    n.path("impact").asText(null),
                    instant(n.path("created_at")),
                    instant(n.path("updated_at")),
                    n.path("shortlink").asText(null),
                    latest));
        }

        List<Maintenance> maintenances = new ArrayList<>();
        for (JsonNode n : root.path("scheduled_maintenances")) {
            maintenances.add(new Maintenance(
                    n.path("id").asText(null),
                    n.path("name").asText(""),
                    n.path("status").asText(null),
                    instant(n.path("scheduled_for")),
                    instant(n.path("scheduled_until")),
                    n.path("shortlink").asText(null)));
        }
        return new Summary(indicator, description, incidents, maintenances, updatedAt);
    }

    private static Instant instant(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText()).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
