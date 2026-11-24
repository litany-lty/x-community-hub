package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.xops.hub.assistant.AssistantStore.object;

/** Versioned source state and draft-facing changes derived from immutable research snapshots. */
public final class AssistantEvidence {
    private AssistantEvidence() { }

    public static void record(AssistantStore.Tx tx, ObjectNode research) {
        Map<String, JsonNode> evidenceById = new LinkedHashMap<>();
        research.path("evidence").forEach(item -> evidenceById.put(item.path("id").asText(), item));
        ArrayNode detected = research.putArray("evidenceChanges");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode read : research.path("sourceReads")) {
            String url = normalizedUrl(read.path("url").asText(""));
            if (url.isBlank() || !seen.add(url)) continue;
            JsonNode evidence = evidenceById.get(read.path("id").asText());
            String checksum = evidence == null ? "" : evidence.path("contentFingerprint").asText("");
            String status = read.path("status").asText("UNKNOWN");
            String id = sourceId(url);
            ObjectNode previous = tx.get("evidence_source", id);
            String type = changeType(previous, checksum, status);
            if (!type.isBlank()) detected.add(change(type, url, previous, checksum, status));
            ObjectNode current = object().put("url", url).put("checksum", checksum).put("status", status)
                    .put("readAt", read.path("readAt").asText("")).put("detail", read.path("detail").asText(""));
            if (previous != null) current.put("previousChecksum", previous.path("checksum").asText(""))
                    .put("previousStatus", previous.path("status").asText(""));
            tx.put("evidence_source", id, current);
        }
        research.put("sourcesChanged", !detected.isEmpty());
    }

    public static ObjectNode changes(AssistantStore.Tx tx, ObjectNode draft) {
        ObjectNode output = object();
        ArrayNode details = output.putArray("evidenceChanges");
        JsonNode research = researchSnapshot(tx, draft);
        compareSources(tx, research, details);
        compareTopic(tx, draft, research, details);
        output.put("sourcesChanged", !details.isEmpty());
        return output;
    }

    private static JsonNode researchSnapshot(AssistantStore.Tx tx, ObjectNode draft) {
        if (draft.path("research").isObject()) return draft.path("research");
        String id = draft.path("researchId").asText("");
        ObjectNode stored = id.isBlank() ? null : tx.get("research", id);
        return stored == null ? object() : stored;
    }

    private static void compareSources(AssistantStore.Tx tx, JsonNode research, ArrayNode details) {
        Map<String, JsonNode> snapshotEvidence = new LinkedHashMap<>();
        research.path("evidence").forEach(item -> snapshotEvidence.put(item.path("id").asText(), item));
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode read : research.path("sourceReads")) {
            String url = normalizedUrl(read.path("url").asText(""));
            if (url.isBlank() || !seen.add(url)) continue;
            ObjectNode current = tx.get("evidence_source", sourceId(url));
            if (current == null) continue;
            JsonNode evidence = snapshotEvidence.get(read.path("id").asText());
            String oldChecksum = evidence == null ? "" : evidence.path("contentFingerprint").asText("");
            String oldStatus = read.path("status").asText("UNKNOWN");
            String currentChecksum = current.path("checksum").asText("");
            String currentStatus = current.path("status").asText("UNKNOWN");
            String type = compare(oldChecksum, oldStatus, currentChecksum, currentStatus);
            if (!type.isBlank()) details.add(object().put("type", type).put("url", url)
                    .put("previousChecksum", oldChecksum).put("currentChecksum", currentChecksum)
                    .put("previousStatus", oldStatus).put("currentStatus", currentStatus));
        }
    }

    private static void compareTopic(AssistantStore.Tx tx, ObjectNode draft, JsonNode research, ArrayNode details) {
        JsonNode snapshot = research.path("snapshot");
        JsonNode oldTopic = snapshot.path("topic");
        String topicId = draft.path("topicId").asText(oldTopic.path("id").asText(""));
        ObjectNode current = topicId.isBlank() ? null : tx.get("topic", topicId);
        if (current == null) {
            if (!topicId.isBlank()) details.add(object().put("type", "TOPIC_REMOVED").put("topicId", topicId));
            return;
        }
        Set<String> oldMembers = textSet(oldTopic.path("materialIds"));
        if (oldMembers.isEmpty()) snapshot.path("materials").forEach(item -> oldMembers.add(item.path("id").asText()));
        Set<String> newMembers = textSet(current.path("materialIds"));
        if (!oldMembers.equals(newMembers)) {
            ObjectNode change = object().put("type", "TOPIC_MEMBERS_CHANGED").put("topicId", topicId);
            change.set("previousMaterialIds", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().addAll(oldMembers.stream().map(com.fasterxml.jackson.databind.node.TextNode::valueOf).toList()));
            change.set("currentMaterialIds", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode().addAll(newMembers.stream().map(com.fasterxml.jackson.databind.node.TextNode::valueOf).toList()));
            details.add(change);
        }
        Map<String, JsonNode> oldMaterials = new LinkedHashMap<>();
        snapshot.path("materials").forEach(item -> oldMaterials.put(item.path("id").asText(), item));
        for (String materialId : oldMembers) {
            JsonNode old = oldMaterials.get(materialId);
            ObjectNode now = tx.get("material", materialId);
            if (old == null || now == null) continue;
            if (!materialFingerprint(old).equals(materialFingerprint(now)))
                details.add(object().put("type", "MATERIAL_TEXT_CHANGED").put("topicId", topicId).put("materialId", materialId));
        }
    }

    private static String changeType(ObjectNode previous, String checksum, String status) {
        if (previous == null) return "";
        return compare(previous.path("checksum").asText(""), previous.path("status").asText("UNKNOWN"), checksum, status);
    }

    private static String compare(String oldChecksum, String oldStatus, String checksum, String status) {
        boolean wasReadable = "READ".equals(oldStatus) && !oldChecksum.isBlank();
        boolean readable = "READ".equals(status) && !checksum.isBlank();
        if (wasReadable && readable && !oldChecksum.equals(checksum)) return "SOURCE_TEXT_UPDATED";
        if (wasReadable && !readable) return "SOURCE_NOW_UNREADABLE";
        if (!wasReadable && readable) return "SOURCE_READABLE_AGAIN";
        return "";
    }

    private static ObjectNode change(String type, String url, JsonNode previous, String checksum, String status) {
        return object().put("type", type).put("url", url)
                .put("previousChecksum", previous == null ? "" : previous.path("checksum").asText(previous.path("contentFingerprint").asText("")))
                .put("currentChecksum", checksum).put("previousStatus", previous == null ? "" : previous.path("status").asText(""))
                .put("currentStatus", status);
    }

    static String normalizedUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || host.isBlank() || uri.getRawUserInfo() != null) return "";
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String path = uri.getPath();
            return new URI(scheme, null, host, port, path == null || path.isBlank() ? "/" : path, uri.getQuery(), null).toString();
        } catch (Exception invalid) {
            return "";
        }
    }

    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> { if (!value.asText("").isBlank()) result.add(value.asText()); });
        return result;
    }
    private static String materialFingerprint(JsonNode material) {
        String value = material.path("contentFingerprint").asText("");
        return value.isBlank() ? hash(material.path("text").asText("") + "\n" + material.path("quotedText").asText("") + "\n" + material.path("articleText").asText("")) : value;
    }
    static String sourceId(String url) { return hash(url); }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
