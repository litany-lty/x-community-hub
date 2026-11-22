package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantEvidenceTest {
    @Test
    void reportsARealChecksumChangeAgainstTheDraftResearchSnapshot() throws Exception {
        var store = new AssistantStore(Files.createTempDirectory("assistant-evidence-test"), new ObjectMapper());
        ObjectNode first = research("checksum-a", "READ");
        store.tx(tx -> { AssistantEvidence.record(tx, first); return null; });
        ObjectNode draft = object().put("topicId", "").set("research", first.deepCopy());

        ObjectNode second = research("checksum-b", "READ");
        store.tx(tx -> { AssistantEvidence.record(tx, second); return null; });
        ObjectNode changes = store.tx(tx -> AssistantEvidence.changes(tx, draft));

        assertThat(changes.path("sourcesChanged").asBoolean()).isTrue();
        assertThat(changes.path("evidenceChanges").path(0).path("type").asText()).isEqualTo("SOURCE_TEXT_UPDATED");
        assertThat(changes.path("evidenceChanges").path(0).path("previousChecksum").asText()).isEqualTo("checksum-a");
        assertThat(changes.path("evidenceChanges").path(0).path("currentChecksum").asText()).isEqualTo("checksum-b");
    }

    @Test
    void factualDraftChangesMustCiteAcceptedResearchExcerpts() throws Exception {
        var engine = new ContentIntelligenceEngine(new ObjectMapper());
        var research = new ContentIntelligenceEngine.Research("s",
                List.of(new ContentIntelligenceEngine.Claim("claim", ContentIntelligenceEngine.EvidenceRelation.SUPPORTS,
                        List.of("source-1"), List.of("source-1#p1"))), List.of(), List.of());
        var invalid = new ObjectMapper().readTree("""
                {"draft":"new","usedClaims":["claim"],"caveats":[],
                 "factChanges":[{"before":"old","after":"new","sourceIds":["source-1"],"excerptIds":["source-9#p1"]}]}
                """);
        assertThatThrownBy(() -> engine.parseDraft(invalid, research))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("factChanges");
    }

    @Test
    void anEditedDraftUsesClaimIdsWithoutParaphrasingPersistedEvidence() throws Exception {
        var mapper = new ObjectMapper();
        var engine = new ContentIntelligenceEngine(mapper);
        var research = new ContentIntelligenceEngine.Research("s",
                List.of(new ContentIntelligenceEngine.Claim("官方公告列出三项新功能", ContentIntelligenceEngine.EvidenceRelation.SUPPORTS,
                        List.of("source-1"), List.of("source-1#p1"))), List.of(), List.of());
        var edited = mapper.readTree("""
                {"needsAdditionalResearch":false,"draft":"新发的公告里有三项更新。","usedClaimIds":["claim-1"],"caveats":[]}
                """);
        assertThat(engine.parseDraft(edited, research).usedClaims()).containsExactly("官方公告列出三项新功能");
        var invented = mapper.readTree("""
                {"draft":"新稿","usedClaimIds":["claim-2"],"caveats":[]}
                """);
        assertThatThrownBy(() -> engine.parseDraft(invented, research))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usedClaimIds");
    }

    private static ObjectNode research(String checksum, String status) {
        ObjectNode research = object();
        research.putArray("sourceReads").add(object().put("id", "source-1").put("url", "https://EXAMPLE.com:443/a#fragment")
                .put("status", status).put("readAt", "2026-11-21T00:00:00Z"));
        research.putArray("evidence").add(object().put("id", "source-1").put("url", "https://example.com/a")
                .put("contentFingerprint", checksum));
        return research;
    }
}
