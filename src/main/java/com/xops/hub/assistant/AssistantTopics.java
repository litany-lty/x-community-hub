package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.xops.hub.provider.XDataProviderException;
import java.util.*;
import java.util.regex.Pattern;
import static com.xops.hub.assistant.AssistantStore.object;

/** Current membership is separate from immutable source text revisions. */
final class AssistantTopics {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+");
    private static final Pattern WORD = Pattern.compile("https?://[^\\s]+|[\\p{IsHan}]+|[\\p{L}\\d][\\p{L}\\d._-]*");
    private AssistantTopics() {}

    static Set<String> terms(String source) {
        var terms=new LinkedHashSet<String>();
        var words=WORD.matcher(source.toLowerCase(Locale.ROOT));
        while (words.find() && terms.size()<80) {
            String token=words.group();
            if (Character.UnicodeScript.of(token.codePointAt(0))==Character.UnicodeScript.HAN) {
                int[] cps=token.codePoints().toArray();
                for(int i=1;i<cps.length && terms.size()<80;i++) terms.add(new String(cps,i-1,2));
            } else if(token.length()>1) terms.add(token);
        }
        return terms;
    }

    static String text(JsonNode material) {
        if(Set.of("NO_ANALYZABLE_TEXT","MEDIA_ONLY","MEDIA_URL_ONLY").contains(material.path("textStatus").asText())) return "";
        return String.join("\n",material.path("text").asText(""),material.path("quotedText").asText(""),material.path("articleText").asText("")).strip();
    }

    static String topicOf(AssistantStore.Tx tx,String materialId) {
        var link=tx.get("membership",materialId);
        return link==null?"":link.path("topicId").asText();
    }

    static ObjectNode required(AssistantStore.Tx tx,String kind,String id) {
        var value=tx.get(kind,id);
        if(value==null) throw new XDataProviderException(404,"记录不存在");
        return value;
    }

    static ObjectNode persist(AssistantStore.Tx tx,String id,ObjectNode topic) {
        var members=(ArrayNode)topic.path("materialIds");
        if(members.isEmpty()) topic.put("status","MERGED");
        else {
            long latestPublished = 0L;
            for (JsonNode member : members) {
                ObjectNode material = tx.get("material", member.asText());
                if (material != null) latestPublished = Math.max(latestPublished, material.path("publishedAt").asLong(0L));
            }
            if (latestPublished > 0L) topic.put("publishedAt", latestPublished);
        }
        var saved=tx.put("topic",id,topic);
        if (!members.isEmpty()) {
            var indexed = terms(topic.path("title").asText() + " " + topic.path("summary").asText());
            for (JsonNode member : members) {
                ObjectNode material = tx.get("material", member.asText());
                if (material != null) indexed.addAll(sourceKeys(material));
            }
            for (String term : indexed) {
                var index=tx.get("topicTerm",term);
                List<String> ids=new ArrayList<>();
                ids.add(id);
                if(index!=null) index.path("ids").forEach(v->{if(ids.size()<20 && !ids.contains(v.asText())) ids.add(v.asText());});
                var entry=object();
                var array=entry.putArray("ids");
                ids.forEach(array::add);
                if(index==null || !array.equals(index.path("ids"))) tx.put("topicTerm",term,entry);
            }
        }
        return saved;
    }

    static String normalizedMaterialText(JsonNode material) {
        return text(material).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String findExactDuplicateTopic(AssistantStore.Tx tx, ObjectNode material, String excludeTopicId) {
        String normalized = normalizedMaterialText(material);
        if (normalized.isBlank()) return "";
        String key = "text:" + AssistantWorkflow.digest(TextNode.valueOf(normalized));
        ObjectNode index = tx.get("topicTerm", key);
        if (index == null) return "";
        for (JsonNode candidate : index.path("ids")) {
            String candidateId = candidate.asText("");
            if (candidateId.isBlank() || candidateId.equals(excludeTopicId)) continue;
            ObjectNode topic = tx.get("topic", candidateId);
            if (topic == null || topic.path("materialIds").isEmpty()) continue;
            for (JsonNode member : topic.path("materialIds")) {
                ObjectNode existing = tx.get("material", member.asText(""));
                if (existing != null && normalized.equals(normalizedMaterialText(existing))) return candidateId;
            }
        }
        return "";
    }

    static ObjectNode fresh(String id,ObjectNode material) {
        String text=text(material);
        var topic=object().put("title",text.codePoints().limit(100).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString())
                .put("summary","").put("status","PENDING").put("id",id);
        topic.putArray("materialIds").add(material.path("id").asText());
        return topic;
    }

    static List<String> links(JsonNode material) {
        var urls = new LinkedHashSet<String>();
        for (String field : List.of("sourceUrl", "articleUrl", "text", "quotedText")) {
            var matches = URL.matcher(material.path(field).asText());
            while (matches.find() && urls.size() < 20) {
                String url = AssistantEvidence.normalizedUrl(matches.group().replaceAll("[.,;!?，。！？）)]+$", ""));
                if (!url.isBlank()) urls.add(url);
            }
        }
        String quotedId = material.path("quotedId").asText();
        if (quotedId.matches("[0-9]+")) urls.add("https://x.com/i/web/status/" + quotedId);
        return List.copyOf(urls);
    }

    private static Set<String> sourceKeys(JsonNode material) {
        var keys = new LinkedHashSet<String>();
        links(material).forEach(link -> keys.add("url:" + link));
        String text = text(material).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (!text.isBlank()) keys.add("text:" + AssistantWorkflow.digest(TextNode.valueOf(text)));
        return keys;
    }

    static ArrayNode candidates(AssistantStore.Tx tx,ObjectNode material,String own) {
        var weights = new LinkedHashMap<String,Integer>();
        var terms = terms(text(material));
        terms.addAll(sourceKeys(material));
        for (String term : terms) {
            var index = tx.get("topicTerm", term);
            int weight = term.startsWith("url:") || term.startsWith("text:") ? 100 : 1;
            if (index != null) index.path("ids").forEach(v -> weights.merge(v.asText(), weight, Integer::sum));
        }
        var ranked = new LinkedHashSet<String>();
        weights.entrySet().stream().filter(e -> !e.getKey().equals(own))
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(10).forEach(e -> ranked.add(e.getKey()));
        tx.page("topic",21,0).forEach(t -> ranked.add(t.path("id").asText()));
        weights.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(21)
                .forEach(e -> ranked.add(e.getKey()));
        var result = JsonNodeFactory.instance.arrayNode();
        for (String id : ranked) {
            if (id.equals(own)) continue;
            var topic = tx.get("topic", id);
            if (topic == null || topic.path("materialIds").isEmpty()) continue;
            var brief = object().put("id", id).put("title", topic.path("title").asText())
                    .put("summary", topic.path("summary").asText()).put("version", topic.path("version").asInt());
            var links = brief.putArray("sourceLinks");
            var claims = brief.putArray("coreClaims");
            for (JsonNode member : topic.path("materialIds")) {
                ObjectNode related = tx.get("material", member.asText());
                if (related == null) continue;
                if (!brief.has("publishedAt")) brief.set("publishedAt", related.path("publishedAt"));
                for (String link : links(related)) if (links.size() < 8) links.add(link);
                String sourceText = text(related);
                if (!sourceText.isBlank()) {
                    int end = sourceText.offsetByCodePoints(0, Math.min(200, sourceText.codePointCount(0, sourceText.length())));
                    claims.add(sourceText.substring(0, end));
                }
                if (claims.size() == 2) break;
            }
            result.add(brief);
            if (result.size() == 20) break;
        }
        return result;
    }

    static ObjectNode snapshot(AssistantStore.Tx tx,String topicId,String selected) {
        var topic=required(tx,"topic",topicId);
        var snap=object().put("topicId",topicId).put("topicVersion",topic.path("version").asInt()).put("selectedMaterialId",selected);
        snap.set("topic",topic);
        var materials=snap.putArray("materials");
        topic.path("materialIds").forEach(id -> {
            ObjectNode member = required(tx,"material",id.asText());
            var link = AssistantPublishing.materialPost(id.asText(), member);
            if (link != null) member.put("sourceUrl", link.url());
            materials.add(member);
        });
        return snap;
    }

    static Set<String> move(AssistantStore.Tx tx,List<String> ids,String target,JsonNode expected,String actor) {
        if(ids.isEmpty()) throw new IllegalArgumentException("请选择素材");
        var affected=new LinkedHashMap<String,ObjectNode>();
        var dest=tx.get("topic",target);
        if(dest!=null) affected.put(target,dest);
        for(String id:ids) {
            String current=topicOf(tx,id);
            affected.computeIfAbsent(current,k->required(tx,"topic",k));
        }
        for(var entry:affected.entrySet())
            if(expected.path(entry.getKey()).asInt(-1)!=entry.getValue().path("version").asInt())
                throw new XDataProviderException(409,"选题成员已更新，请刷新后确认");
        if(dest==null) {
            dest=fresh(target,required(tx,"material",ids.get(0)));
            dest.putArray("materialIds");
            affected.put(target,dest);
        }
        var moved=dest.withArray("materialIds");
        for(String id:ids) {
            var membership = required(tx, "membership", id);
            String from = membership.path("topicId").asText();
            if(target.equals(from)) continue;
            var old=affected.get(from).withArray("materialIds");
            for(int i=old.size()-1;i>=0;i--) if(id.equals(old.get(i).asText())) old.remove(i);
            moved.add(id);
            if ("OWNER_LINK".equals(membership.path("actor").asText())) {
                var material = required(tx, "material", id);
                var importedAt = material.path("ownerImportedAt");
                if (!importedAt.isIntegralNumber() || importedAt.asLong() <= 0) {
                    // Older links use collectedAt (or the head timestamp) until their membership actor changes.
                    long originalImport = material.path("collectedAt").asLong(material.path("updatedAt").asLong());
                    if (originalImport > 0) tx.put("material", id, material.put("ownerImportedAt", originalImport));
                }
            }
            tx.put("membership",id,membership.put("topicId",target).put("fromTopic",from).put("actor",actor));
        }
        for(var e:affected.entrySet()) {
            var t=e.getValue();
            if(t.path("materialIds").isEmpty()) t.put("mergedInto",target);
            else {
                t.remove("mergedInto");
                String lead=t.path("materialIds").get(0).asText();
                t.put("title",fresh(e.getKey(),required(tx,"material",lead)).path("title").asText())
                        .put("summary","").put("status","PENDING");
                t.remove(List.of("score","valueScore","reachScore","reason","assessment"));
            }
            persist(tx,e.getKey(),t);
        }
        return affected.keySet();
    }
}
