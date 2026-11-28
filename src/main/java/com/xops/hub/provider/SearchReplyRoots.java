package com.xops.hub.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xops.hub.store.AccountStore;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Conversation evidence for one verified account snapshot; page-local edges never cross requests. */
final class SearchReplyRoots {
    private static final Pattern POST_ID = Pattern.compile("[0-9]{2,20}");
    private final Map<String, Post> stored;
    // Completed parent lookups only. Active requests keep their independent cancellation boundaries.
    private final Map<String, List<Post>> threads = new ConcurrentHashMap<>();

    static SearchReplyRoots empty() {
        return new SearchReplyRoots(Map.of());
    }

    List<Post> thread(String parentId) {
        return threads.get(parentId);
    }

    void rememberThread(String parentId, List<Post> chain) {
        threads.putIfAbsent(parentId, List.copyOf(chain));
    }

    private SearchReplyRoots(Map<String, Post> stored) {
        this.stored = stored;
    }

    static SearchReplyRoots from(AccountStore.StoredAccount account, String ownerId, String handle) {
        if (account == null || account.profile() == null || ownerId == null || ownerId.isBlank()
                || !ownerId.equals(account.profile().id()) || !handle.equalsIgnoreCase(account.profile().username())) return empty();
        Map<String, Post> known = new HashMap<>();
        for (var t : account.tweets()) {
            if (t.kind() == 'T') continue;
            boolean reply = t.kind() == 'R' || (t.flags() & 4) != 0;
            String root = t.conversationId() == null && !reply ? t.id() : t.conversationId();
            String parentHandle = (t.flags() & 4) != 0 ? handle : t.target();
            addStored(known, new Post(t.id(), ownerId, handle, t.createdAt(), t.inReplyToId(),
                    reply ? parentHandle : null, root), reply);
        }
        for (var i : account.inbound()) {
            if (i.peer() == null || i.peer().isBlank()) continue;
            if (i.peerId() == null || i.peerId().isBlank()) continue;
            addStored(known, new Post(i.id(), i.peerId(), i.peer(), i.createdAt(), i.inReplyToId(),
                    i.inReplyToUser(), i.conversationId()), i.kind() == 'R' || i.inReplyToId() != null);
        }
        return new SearchReplyRoots(known);
    }

    private static void addStored(Map<String, Post> known, Post p, boolean reply) {
        if (!validId(p.id()) || !validId(p.root()) || p.time() <= 0) return;
        if (reply) {
            if (!validId(p.parent()) || p.id().equals(p.parent()) || p.parentHandle() == null || p.parentHandle().isBlank() || p.id().equals(p.root())) return;
        } else if (p.parent() != null || !p.id().equals(p.root())) return;
        known.put(p.id(), p);
    }

    private static boolean validId(String id) {
        return id != null && POST_ID.matcher(id).matches();
    }

    record Post(String id, String authorId, String handle, long time, String parent, String parentHandle, String root) {
        static Post fromNormalized(JsonNode tweet, long time) {
            JsonNode author = tweet.path("author");
            return new Post(tweet.path("id").asText(), author.path("id").asText(), author.path("userName").asText(), time,
                    tweet.path("inReplyToId").asText(null), tweet.path("inReplyToUsername").asText(null),
                    tweet.path("conversationId").asText(null));
        }

        boolean sameIdentityAndEdge(Post other) {
            return authorId.equals(other.authorId) && handle.equalsIgnoreCase(other.handle) && time == other.time
                    && Objects.equals(parent, other.parent)
                    && Objects.toString(parentHandle, "").equalsIgnoreCase(Objects.toString(other.parentHandle, ""));
        }
    }

    final class Page {
        private final Map<String, Post> posts = new HashMap<>();

        void add(Post post) {
            Post previous = posts.putIfAbsent(post.id(), post);
            if (previous != null && !previous.equals(post)) throw invalid();
        }

        /** Only explicit roots, or continuous parent links ending at one, may advance search coverage. */
        Set<String> fill(JsonNode tweets) {
            Set<String> missing = new LinkedHashSet<>();
            for (JsonNode tweet : tweets) {
                if (!tweet.path("isReply").asBoolean()) continue;
                Post reply = posts.get(tweet.path("id").asText());
                Post current = reply;
                Set<String> seen = new HashSet<>();
                String root = null;
                while (current != null) {
                    if (!seen.add(current.id())) throw invalid();
                    Post old = stored.get(current.id());
                    if (old != null && !old.sameIdentityAndEdge(current)) throw invalid();
                    String evidence = current.root();
                    if (evidence == null && old != null) evidence = old.root();
                    if (evidence != null) {
                        if (root != null && !root.equals(evidence)) throw invalid();
                        root = evidence;
                    }
                    if (current.parent() == null) break;
                    Post parent = posts.get(current.parent());
                    if (parent == null) parent = stored.get(current.parent());
                    if (parent == null) {
                        if (root == null) missing.add(current.parent());
                        break;
                    }
                    if (!parent.handle().equalsIgnoreCase(current.parentHandle()) || parent.time() > current.time()) throw invalid();
                    if (current == reply && parent.authorId() != null && !parent.authorId().isBlank()) {
                        ((ObjectNode) tweet).put("inReplyToUserId", parent.authorId());
                    }
                    current = parent;
                }
                if (root != null) {
                    Post anchor = posts.get(root);
                    if (anchor == null) anchor = stored.get(root);
                    if (root.equals(reply.id()) || (anchor != null
                            && (anchor.parent() != null || anchor.time() > reply.time()))) {
                        throw invalid();
                    }
                    ((ObjectNode) tweet).put("conversationId", root);
                }
            }
            return missing;
        }
    }

    static XDataProviderException invalid() {
        return new XDataProviderException(502, "公开搜索无法核实回复的根会话");
    }
}
