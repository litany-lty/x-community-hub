package com.xops.hub.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates structured model output before it can change assistant state. */
public final class ContentIntelligenceEngine {
    private final ObjectMapper mapper;

    public ContentIntelligenceEngine(ObjectMapper mapper) { this.mapper = mapper; }

    public JsonNode parseJson(TextModelClient.ModelResult result) {
        try {
            JsonNode value = mapper.readTree(result.text());
            if (value == null || !value.isObject()) throw new IllegalArgumentException("model output must be a JSON object");
            return value;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("model output is not valid JSON", e);
        }
    }

    public MergeDecision parseMergeDecision(JsonNode json, List<String> candidateIds) {
        MergeKind kind = requiredEnum(json, "decision", MergeKind.class);
        String candidateId = text(json, "candidateId");
        boolean requiresCandidate = kind != MergeKind.NEW_TOPIC && kind != MergeKind.UNCERTAIN;
        if (requiresCandidate && candidateId.isBlank() || !candidateId.isBlank() && !candidateIds.contains(candidateId)) {
            throw new IllegalArgumentException("candidateId must reference a provided candidate");
        }
        if (kind == MergeKind.NEW_TOPIC && !candidateId.isBlank()) {
            throw new IllegalArgumentException("NEW_TOPIC cannot reference candidateId");
        }
        return new MergeDecision(kind, candidateId, requiredEnum(json, "confidence", Confidence.class),
                requiredText(json, "reason"), strings(json.path("newFacts")), strings(json.path("conflicts")));
    }

    public Score parseScore(JsonNode json, ScoreWeights weights, boolean verified) {
        Dimension topic = dimension(json.path("topic"), weights.topic());
        Dimension reach = dimension(json.path("reach"), weights.reach());
        int composite = (int) Math.round((topic.score() + reach.score()) / 2.0);
        return new Score(requiredText(json, "title"), requiredText(json, "summary"), strings(json.path("tags")),
                topic.score(), reach.score(), composite, topic.coverage(), reach.coverage(),
                verified ? EvidenceStatus.RESEARCH_VERIFIED : EvidenceStatus.PENDING_VERIFICATION,
                topic.reasons(), reach.reasons());
    }

    public Research parseResearch(JsonNode json, List<String> sourceIds) {
        return parseResearch(json, sourceIds, Map.of());
    }

    public Research parseResearch(JsonNode json, List<String> sourceIds, Map<String, Set<String>> excerptIdsBySource) {
        Set<String> allowed = Set.copyOf(sourceIds);
        List<Claim> claims = new ArrayList<>();
        for (JsonNode claim : json.path("claims")) {
            List<String> refs = strings(claim.path("sourceIds"));
            if (!allowed.containsAll(refs)) throw new IllegalArgumentException("claim sourceIds must reference actually read sources");
            EvidenceRelation relation=requiredEnum(claim,"relation",EvidenceRelation.class);
            if(relation!=EvidenceRelation.UNVERIFIED && refs.isEmpty()) throw new IllegalArgumentException("a factual claim requires a read source");
            List<String> excerpts = strings(claim.path("excerptIds"));
            Set<String> allowedExcerpts = new LinkedHashSet<>();
            refs.forEach(source -> allowedExcerpts.addAll(excerptIdsBySource.getOrDefault(source, Set.of())));
            if (!excerptIdsBySource.isEmpty() && relation != EvidenceRelation.UNVERIFIED && excerpts.isEmpty())
                throw new IllegalArgumentException("a factual claim requires an extracted text citation");
            if (!allowedExcerpts.containsAll(excerpts)) throw new IllegalArgumentException("claim excerptIds must belong to its sourceIds");
            if (!excerptIdsBySource.isEmpty() && relation != EvidenceRelation.UNVERIFIED
                    && refs.stream().anyMatch(source -> excerpts.stream().noneMatch(excerptIdsBySource.getOrDefault(source, Set.of())::contains)))
                throw new IllegalArgumentException("each claim source must have an extracted text citation");
            claims.add(new Claim(requiredText(claim, "claim"), relation, refs, excerpts));
        }
        return new Research(requiredText(json, "summary"), claims, strings(json.path("angles")), strings(json.path("unverified")));
    }

    public Draft parseDraft(JsonNode json, Research research) {
        String draft = requiredText(json, "draft");
        Map<String, Claim> acceptedClaims = indexedClaims(research);
        List<String> claims;
        if (json.has("usedClaimIds")) {
            List<String> ids = strings(json.path("usedClaimIds"));
            if (!acceptedClaims.keySet().containsAll(ids))
                throw new IllegalArgumentException("usedClaimIds must come from the accepted research result");
            claims = ids.stream().distinct().map(id -> acceptedClaims.get(id).claim()).toList();
        } else {
            claims = strings(json.path("usedClaims"));
            Set<String> allowed = new LinkedHashSet<>();
            acceptedClaims.values().stream().map(Claim::claim).forEach(allowed::add);
            if (!allowed.containsAll(claims)) throw new IllegalArgumentException("usedClaims must come from the accepted research result");
        }
        List<FactChange> changes = new ArrayList<>();
        for (JsonNode change : json.path("factChanges")) {
            List<String> sources = strings(change.path("sourceIds"));
            List<String> excerpts = strings(change.path("excerptIds"));
            String after = requiredText(change, "after");
            boolean accepted = !sources.isEmpty() && !excerpts.isEmpty() && research.claims().stream()
                    .anyMatch(claim -> claim.relation() != EvidenceRelation.UNVERIFIED && claim.sourceIds() != null && claim.excerptIds() != null
                            && claim.sourceIds().containsAll(sources) && claim.excerptIds().containsAll(excerpts));
            if (!accepted) throw new IllegalArgumentException("factChanges must cite accepted research evidence");
            changes.add(new FactChange(change.path("before").asText(""), after, sources, excerpts));
        }
        return new Draft(draft, claims, strings(json.path("caveats")), changes);
    }

    static Map<String, Claim> indexedClaims(Research research) {
        Map<String, Claim> indexed = new java.util.LinkedHashMap<>();
        for (int i = 0; i < research.claims().size(); i++) {
            indexed.put("claim-" + (i + 1), research.claims().get(i));
        }
        return indexed;
    }

    public static ScoreWeights defaultWeights() {
        return new ScoreWeights(
                Map.of("novelty", 25, "traceability", 25, "importance", 20, "timeliness", 15, "depth", 15),
                Map.of("hook", 25, "shareability", 25, "clarity", 20, "followReason", 20, "signals", 10));
    }

    static ScoreWeights scoreWeights(JsonNode config) {
        ScoreWeights defaults = defaultWeights();
        if (config.isMissingNode()) return defaults;
        if (!config.isObject()) throw new IllegalArgumentException("评分权重必须是对象");
        config.fieldNames().forEachRemaining(key -> {
            if (!Set.of("topic", "reach").contains(key)) throw new IllegalArgumentException("未知评分维度 " + key);
        });
        return new ScoreWeights(scoreDimension(config.path("topic"), defaults.topic()),
                scoreDimension(config.path("reach"), defaults.reach()));
    }

    private static Map<String, Integer> scoreDimension(JsonNode config, Map<String, Integer> defaults) {
        if (config.isMissingNode()) return defaults;
        if (!config.isObject()) throw new IllegalArgumentException("评分分项必须是对象");
        Map<String, Integer> weights = new java.util.LinkedHashMap<>(defaults);
        config.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (!defaults.containsKey(entry.getKey()) || !value.isIntegralNumber() || !value.canConvertToInt()
                    || value.asInt() < 0 || value.asInt() > 100)
                throw new IllegalArgumentException("评分分项无效 " + entry.getKey());
            weights.put(entry.getKey(), value.asInt());
        });
        if (weights.values().stream().allMatch(weight -> weight == 0))
            throw new IllegalArgumentException("评分维度至少需要一个正权重");
        return weights;
    }

    private Dimension dimension(JsonNode node, Map<String, Integer> weights) {
        JsonNode components = node.path("components");
        double weighted = 0;
        int covered = 0;
        int possible = weights.values().stream().mapToInt(Integer::intValue).sum();
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            JsonNode value = components.path(entry.getKey());
            if (!value.isNumber() && !value.isNull() && !value.isMissingNode()) {
                throw new IllegalArgumentException(entry.getKey() + " score must be a number or null");
            }
            if (value.isNumber()) {
                double score = value.doubleValue();
                if (!Double.isFinite(score) || score < 0 || score > 100) throw new IllegalArgumentException(entry.getKey() + " score must be 0..100");
                weighted += score * entry.getValue();
                covered += entry.getValue();
            }
        }
        if (covered == 0) throw new IllegalArgumentException("at least one score component is required");
        return new Dimension((int) Math.round(weighted / covered), covered / (double) possible, strings(node.path("reasons")));
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (!node.isMissingNode() && !node.isNull() && !node.isArray()) throw new IllegalArgumentException("expected an array of strings");
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("array values must be non-empty strings");
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : ""; }
    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static <T extends Enum<T>> T requiredEnum(JsonNode node, String field, Class<T> type) {
        try { return Enum.valueOf(type, requiredText(node, field)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(field + " is invalid", e); }
    }

    public enum MergeKind { DUPLICATE, SAME_EVENT_NEW_INFORMATION, CONFLICT, NEW_TOPIC, UNCERTAIN }
    public enum Confidence { LOW, MEDIUM, HIGH }
    public enum EvidenceStatus { PENDING_VERIFICATION, RESEARCH_VERIFIED }
    public enum EvidenceRelation { SUPPORTS, REFUTES, BACKGROUND, UNVERIFIED }
    public record MergeDecision(MergeKind kind, String candidateId, Confidence confidence, String reason,
                                List<String> newFacts, List<String> conflicts) { }
    public record ScoreWeights(Map<String, Integer> topic, Map<String, Integer> reach) {
        public ScoreWeights { topic = Map.copyOf(topic); reach = Map.copyOf(reach); }
    }
    public record Score(String title, String summary, List<String> tags, int topicValue, int reachPotential, int composite, double topicCoverage,
                        double reachCoverage, EvidenceStatus evidenceStatus, List<String> topicReasons,
                        List<String> reachReasons) { }
    public record Claim(String claim, EvidenceRelation relation, List<String> sourceIds, List<String> excerptIds) { }
    public record Research(String summary, List<Claim> claims, List<String> angles, List<String> unverified) { }
    public record FactChange(String before, String after, List<String> sourceIds, List<String> excerptIds) { }
    public record Draft(String text, List<String> usedClaims, List<String> caveats, List<FactChange> factChanges) { }
    private record Dimension(int score, double coverage, List<String> reasons) { }
}
