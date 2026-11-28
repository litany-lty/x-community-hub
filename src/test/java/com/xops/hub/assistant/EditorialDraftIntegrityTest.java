package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.provider.XDataProviderException;
import com.xops.hub.service.ScheduledTweetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditorialDraftIntegrityTest {
    @TempDir Path directory;
    private AssistantStore store;

    @BeforeEach void bindOwner() {
        store = new AssistantStore(directory, new ObjectMapper());
        store.tx(tx -> tx.put("meta", "owner", object().put("bindingSource", "EXPLICIT")
                .put("accountId", "501").put("username", "elidlicom")));
    }

    private ObjectNode webPage(String body) {
        URI url = URI.create("https://example.org/article");
        var read = new TextSourceReader.ReadResult(url, url, "text/html", "公告", body, "body",
                Instant.now(), TextSourceReader.Status.READ, "");
        return AssistantWorkflow.buildWebLinkMaterial(url.toString(), read, System.currentTimeMillis());
    }

    private String ingest(ObjectNode material) {
        return store.tx(tx -> AssistantWorkflow.ingestDirectLinkMaterial(tx, material));
    }

    private void move(String material, String destination) {
        store.tx(tx -> {
            String origin = AssistantTopics.topicOf(tx, material);
            var versions = object().put(origin, tx.get("topic", origin).get("version").asInt());
            var target = tx.get("topic", destination);
            if (target != null) versions.put(destination, target.get("version").asInt());
            return AssistantTopics.move(tx, List.of(material), destination, versions, "OWNER");
        });
    }

    @Test void selectedWebArticleCannotQuoteAnotherMemberOfItsFrozenTopic() {
        var web = webPage("作者独立刊登的文章");
        String id = web.get("id").asText();
        String topic = ingest(web);
        ingest(object().put("id", "101").put("text", "另一位作者的推文"));
        move("101", topic);
        store.tx(tx -> {
            var fast = AssistantWorkflow.research(tx, object().put("mode", "FAST").put("materialId", id).put("clientRequestId", "fast"));
            var draft = object().put("topicId", topic).put("materialId", id).put("text", "网页成稿");
            draft.set("research", fast.at("/input/research"));
            assertEquals(2, draft.at("/research/snapshot/materials").size());
            assertEquals(1, draft.at("/research/materialEvidence").size());
            var saved = tx.put("draft", "web-draft", draft);
            assertTrue(AssistantPublishing.withDraftSourcePost(tx, saved).get("quoteSourcePost").isNull());
            return null;
        });
        var service = new AssistantPublishing(store, mock(ScheduledTweetService.class));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("501", "web-draft", 1, "wrong", "网页成稿", 123, List.of(), true, "101"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void splitRetainsTheLegacyDraftsSelectedSourceAcrossHistoryResearchAndRevision(boolean embedded) {
        String origin = ingest(object().put("id", "101").put("text", "A"));
        ingest(object().put("id", "202").put("text", "B"));
        move("202", origin);
        store.tx(tx -> {
            ObjectNode research = object().set("snapshot", AssistantTopics.snapshot(tx, origin, "202"));
            tx.put("research", "old-study", research);
            var draft = object().put("topicId", origin).put("researchId", "old-study").put("text", "B 稿");
            if (embedded) draft.set("research", research);
            tx.put("draft", "legacy", draft);
            return AssistantWorkflow.saveDraft(tx, "legacy", object().put("version", 1).put("text", "手改 B 稿"));
        });
        move("202", "new-topic");
        store.tx(tx -> {
            assertTrue(tx.topicRecords("draft", origin, -1, 0).isEmpty());
            var visible = AssistantPublishing.withDraftSourcePost(tx, tx.topicRecords("draft", "new-topic", -1, 0).get(0));
            assertEquals("202", visible.path("materialId").asText());
            assertEquals("202", visible.at("/quoteSourcePost/tweetId").asText());
            for (ObjectNode version : tx.versions("draft", "legacy"))
                assertEquals("202", AssistantPublishing.withDraftSourcePost(tx, version).at("/quoteSourcePost/tweetId").asText());
            var request = object().put("draftId", "legacy").put("version", 2).put("clientRequestId", "upgrade");
            var upgrade = AssistantWorkflow.research(tx, request);
            assertEquals("new-topic", upgrade.path("topicId").asText());
            assertEquals("202", upgrade.at("/input/selectedMaterialId").asText());
            assertThrows(XDataProviderException.class, () -> AssistantWorkflow.research(tx, request.deepCopy()
                    .put("clientRequestId", "other-source").put("materialId", "101")));
            var edited = AssistantWorkflow.optimize(tx, "legacy", object().put("version", 2).put("instruction", "修改")
                    .put("clientRequestId", "revision"));
            assertEquals("202", edited.at("/input/selectedMaterialId").asText());
            assertEquals("202", edited.at("/input/materials/0/id").asText());
            return null;
        });
    }

    @Test void correctedWebSourceInvalidatesItsEarlierResearchInFastDraftsAndOptimizations() {
        var first = webPage("原定价格 50 元");
        String id = first.get("id").asText();
        String topic = ingest(first);
        store.tx(tx -> {
            var original = object().put("draftable", true).put("quality", "SUPPORTED").put("materialId", id);
            original.set("snapshot", AssistantTopics.snapshot(tx, topic, id));
            original.putArray("claims").addObject().put("claim", "此前深挖确认价格 50 元").putArray("sourceIds").add("material-" + id + "-link");
            tx.put("research", "deep", original);
            tx.put("task", "deep", object().put("kind", "RESEARCH").put("topicId", topic).put("materialId", id));
            tx.put("draft", "base", object().put("topicId", topic).put("materialId", id).put("text", "公告")
                    .put("researchId", "deep"));
            assertTrue(AssistantWorkflow.research(tx, object().put("mode", "FAST").put("materialId", id)
                    .put("clientRequestId", "unchanged")).at("/input/research/claims").toString().contains("此前深挖"));
            return null;
        });
        ingest(webPage("作者更正价格为 20 元"));
        store.tx(tx -> {
            var fresh = AssistantWorkflow.research(tx, object().put("mode", "FAST").put("materialId", id).put("clientRequestId", "corrected"));
            String claims = fresh.at("/input/research/claims").toString();
            assertTrue(claims.contains("20 元"));
            assertFalse(claims.contains("50 元"));
            var edit = AssistantWorkflow.optimize(tx, "base", object().put("version", 1)
                    .put("instruction", "修订").put("clientRequestId", "revise"));
            assertFalse(edit.at("/input/research/claims").toString().contains("50 元"));
            assertEquals("作者更正价格为 20 元", edit.at("/input/materials/0/articleText").asText());
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void soleFrozenMaterialFollowsItsSplitForDisplayResearchAndOptimization(boolean embedded) {
        String origin = ingest(object().put("id", "202").put("text", "B 冰川观测"));
        store.tx(tx -> {
            ObjectNode snapshot = AssistantTopics.snapshot(tx, origin, "202");
            snapshot.remove("selectedMaterialId");
            ObjectNode research = object().set("snapshot", snapshot);
            var draft = object().put("topicId", origin).put("text", "B 稿");
            if (embedded) draft.set("research", research);
            else {
                tx.put("research", "legacy-study", research);
                draft.put("researchId", "legacy-study");
            }
            tx.put("draft", "legacy", draft);
            return tx.put("publication", "receipt", object().put("topicId", origin).put("draftId", "legacy").put("draftVersion", 1));
        });
        ingest(object().put("id", "101").put("text", "A 温室观测"));
        move("101", origin);
        move("202", "split");
        store.tx(tx -> {
            assertTrue(tx.topicRecords("draft", origin, -1, 0).isEmpty());
            assertEquals(1, tx.topicRecords("publication", "split", -1, 0).size());
            var visible = AssistantPublishing.withDraftSourcePost(tx, tx.topicRecords("draft", "split", -1, 0).get(0));
            assertEquals("202", visible.path("materialId").asText());
            assertEquals("202", visible.at("/quoteSourcePost/tweetId").asText());
            var upgrade = AssistantWorkflow.research(tx, object().put("draftId", "legacy").put("version", 1).put("clientRequestId", "upgrade"));
            var edit = AssistantWorkflow.optimize(tx, "legacy", object().put("version", 1).put("instruction", "改稿").put("clientRequestId", "edit"));
            for (var task : List.of(upgrade, edit)) {
                assertEquals("split", task.path("topicId").asText());
                assertEquals("202", task.at("/input/selectedMaterialId").asText());
                assertEquals(1, task.at("/input/materials").size());
                assertEquals("B 冰川观测", task.at("/input/materials/0/text").asText());
            }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void correctedPageProvidesOnlyItsNewPriceToTheWriter(boolean staleStoredSummary) {
        var page = webPage("旧公告收取 50 元");
        String topic = ingest(page);
        ingest(webPage("公告更正为 20 元"));
        var input = store.tx(tx -> {
            var row = tx.get("topic", topic);
            assertEquals("公告更正为 20 元", row.path("summary").asText());
            if (staleStoredSummary) {
                tx.put("topic", topic, row.put("summary", "旧公告收取 50 元"));
                var rich = object().put("materialId", page.path("id").asText()).put("draftable", true).put("summary", "旧公告收取 50 元");
                rich.set("snapshot", AssistantTopics.snapshot(tx, topic, page.path("id").asText()));
                rich.putArray("claims").addObject().put("claim", "公告更正为 20 元");
                tx.put("research", "old-input", rich);
                tx.put("task", "old-input", object().put("kind", "RESEARCH").put("topicId", topic).put("materialId", page.path("id").asText()));
            }
            return (ObjectNode) AssistantWorkflow.research(tx, object().put("mode", "FAST").put("materialId", page.path("id").asText())
                    .put("clientRequestId", "fresh")).get("input");
        });
        assertEquals("公告更正为 20 元", input.at("/research/summary").asText());
        List<String> prompts = new java.util.ArrayList<>();
        TextModelClient model = (binding, request) -> {
            prompts.add(request.userText());
            var reply = object().put("draft", "以更正公告为准。");
            reply.putArray("usedClaimIds").add("claim-1");
            reply.putArray("caveats");
            reply.putArray("factChanges");
            return new TextModelClient.ModelResult(reply.toString(), List.of(), null, "fixture", object(), Map.of());
        };
        var env = new MockEnvironment().withProperty(AssistantIntelligence.API_KEY_ENV, "local-fixture");
        var models = new AssistantIntelligence(new ObjectMapper(), env, model, mock(TextSourceReader.class));
        models.run("DRAFT", object().put("provider", "commandcode").put("providerManuallySelected", true)
                .put("protocol", "MESSAGES").put("model", "fixture").put("contextTokens", 32000), input);
        assertFalse(prompts.isEmpty());
        assertTrue(prompts.stream().allMatch(prompt -> prompt.contains("20 元") && !prompt.contains("50 元")));
    }

    @Test void reassignedAliasKeepsItsFrozenQuoteAndCannotFeedTheOtherPostToARevision() {
        var material = object().put("id", "old-key").put("text", "冰川考察").put("sourceUrl", "https://x.com/first/status/101");
        String topic = ingest(material);
        store.tx(tx -> {
            var study = object();
            study.putObject("snapshot").putArray("materials").addObject().put("id", "old-key");
            study.putArray("materialEvidence").addObject().put("id", "material-old-key-author").put("url", material.path("sourceUrl").asText());
            return tx.put("draft", "from-first", object().put("topicId", topic).put("materialId", "old-key").put("text", "冰川稿")
                    .set("research", study));
        });
        ingest(material.deepCopy().put("text", "温室试验").put("sourceUrl", "https://x.com/second/status/202"));
        store.tx(tx -> {
            assertEquals("101", AssistantPublishing.draftSourcePost(tx, tx.get("draft", "from-first")).tweetId());
            assertEquals(409, assertThrows(XDataProviderException.class, () -> AssistantWorkflow.research(tx, object()
                    .put("clientRequestId", "research").put("draftId", "from-first").put("version", 1))).getStatusCode());
            assertEquals(409, assertThrows(XDataProviderException.class, () -> AssistantWorkflow.optimize(tx, "from-first", object()
                    .put("clientRequestId", "revise").put("instruction", "核实").put("version", 1))).getStatusCode());
            return null;
        });
    }

    @Test void observationsFollowTheManualPublicationEvenIfTheOlderObservationLacksItsMaterialId() {
        String origin = ingest(object().put("id", "101").put("text", "A"));
        ingest(object().put("id", "202").put("text", "B"));
        move("202", origin);
        var clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        long now = clock.millis();
        store.tx(tx -> tx.put("publication", "manual-b", object().put("source", "MANUAL_QUOTE_REPOST")
                .put("topicId", origin).put("materialId", "202").put("publishedTweetId", "9000")
                .put("status", "PUBLISHED").put("publishedAt", now - 86_400_000)));
        var client = mock(AssistantObservations.ObservationClient.class);
        when(client.post("501", "9000")).thenReturn(object().put("observedAt", now).put("views", 357));
        var observations = new AssistantObservations(store, () -> {}, client, clock);
        try { observations.runTick(); } finally { observations.stop(); }
        store.tx(tx -> {
            var row = tx.get("observation", "9000:24");
            assertEquals("202", row.path("materialId").asText());
            return tx.put("observation", "9000:72", row.deepCopy().put("windowHours", 72).without("materialId"));
        });
        move("202", "new-topic");
        store.tx(tx -> {
            assertTrue(tx.topicRecords("observation", origin, -1, 0).isEmpty());
            var rows = tx.topicRecords("observation", "new-topic", -1, 0);
            assertEquals(2, rows.size());
            assertTrue(rows.stream().allMatch(row -> row.path("views").asInt() == 357));
            return null;
        });
    }
}
