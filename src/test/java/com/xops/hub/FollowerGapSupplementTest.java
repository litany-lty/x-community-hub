package com.xops.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xops.hub.store.AccountStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class FollowerGapSupplementTest {
    private static final String SELF_ID = "90071992547409931";
    // Synthetic IDs above JavaScript's safe integer range exercise string-based identity matching.
    private static final Map<String, String> MISSING = Map.of(
            "90071992547409932", "fixture_peer_a",
            "90071992547409933", "fixture_peer_b",
            "90071992547409934", "fixture_peer_c",
            "90071992547409935", "fixture_peer_d");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void exhaustedProfileListIsCompletedFromStableIdsAndOnlyMissingProfilesAreHydrated() throws Exception {
        var requests = new CopyOnWriteArrayList<URI>();
        var fixture = new ExactFollowProviderTest();
        try (var f = fixture.new Fixture(uri -> reply(uri, requests, true, false))) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(AccountStore.followerUsers(result.store())).extracting(u -> u.id())
                    .containsExactlyInAnyOrderElementsOf(allIds());
            assertThat(result.store().followersTruncated()).isFalse();
            assertThat(result.store().graphRefreshFailed()).isFalse();
            assertThat(result.store().profile().followersCount()).isEqualTo(5);
            assertThat(requests).filteredOn(u -> u.getPath().endsWith("/followers_ids")).hasSize(1);
            var idsRequest = requests.stream().filter(u -> u.getPath().endsWith("/followers_ids")).findFirst().orElseThrow();
            assertThat(query(idsRequest).get("userId")).isEqualTo(SELF_ID);
            assertThat(query(idsRequest).get("count")).isEqualTo("5000");
            var hydration = requests.stream().filter(u -> u.getPath().endsWith("/batch_info_by_ids")).toList();
            assertThat(hydration).hasSize(1);
            assertThat(query(hydration.get(0)).get("userIds").split(","))
                    .containsExactlyInAnyOrderElementsOf(MISSING.keySet());
        }
    }

    @Test void failedProfileHydrationKeepsOriginalFollowersAndIncompleteStatus() throws Exception {
        var requests = new CopyOnWriteArrayList<URI>();
        var fixture = new ExactFollowProviderTest();
        try (var f = fixture.new Fixture(uri -> reply(uri, requests, true, true))) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(AccountStore.followerUsers(result.store())).extracting(u -> u.id()).containsExactly("100");
            assertThat(result.store().profile().followersCount()).isEqualTo(5);
            assertThat(result.store().followersTruncated()).isTrue();
            assertThat(requests).filteredOn(u -> u.getPath().endsWith("/batch_info_by_ids")).hasSize(1);
        }
    }

    @Test void completeOriginalFollowerListDoesNotSpendOnSupplementEndpoints() throws Exception {
        var requests = new CopyOnWriteArrayList<URI>();
        var fixture = new ExactFollowProviderTest();
        try (var f = fixture.new Fixture(uri -> reply(uri, requests, false, false))) {
            var result = f.provider.syncStore("self", null, 2, true, null);
            assertThat(result.store().followersTruncated()).isFalse();
            assertThat(AccountStore.followerUsers(result.store())).hasSize(5);
            assertThat(requests).noneMatch(u -> u.getPath().endsWith("/followers_ids")
                    || u.getPath().endsWith("/batch_info_by_ids"));
        }
    }

    private ExactFollowProviderTest.Reply reply(URI uri, List<URI> requests, boolean gap, boolean hydrationFails) {
        requests.add(uri);
        String path = uri.getPath();
        if (path.endsWith("/info")) return ok(Map.of("data", Map.of(
                "id", SELF_ID, "userName", "self", "following", 0, "followers", 5)));
        if (path.endsWith("/followers")) return ok(Map.of("followers", gap
                ? List.of(user("100", "listed")) : allProfiles(), "status", "success",
                "has_next_page", false, "next_cursor", "0", "code", 0));
        if (path.endsWith("/followers_ids")) return ok(Map.of("ids", allIds(), "status", "success",
                "has_next_page", false, "next_cursor", "0", "code", 0));
        if (path.endsWith("/batch_info_by_ids")) return hydrationFails
                ? new ExactFollowProviderTest.Reply(502, "{\"error\":\"upstream failure\"}")
                : ok(Map.of("status", "success", "msg", "", "users", MISSING.entrySet().stream()
                        .map(e -> user(e.getKey(), e.getValue())).toList()));
        return ok(Map.of("tweets", List.of(), "has_next_page", false, "next_cursor", "0"));
    }

    private List<String> allIds() {
        var ids = new ArrayList<String>();
        ids.add("100"); ids.addAll(new TreeSet<>(MISSING.keySet())); return ids;
    }

    private List<Map<String, Object>> allProfiles() {
        var users = new ArrayList<Map<String, Object>>(); users.add(user("100", "listed"));
        MISSING.forEach((id, handle) -> users.add(user(id, handle))); return users;
    }

    private Map<String, Object> user(String id, String handle) {
        return Map.of("id", id, "userName", handle, "protected", false);
    }

    private ExactFollowProviderTest.Reply ok(Object body) {
        try { return ExactFollowProviderTest.Reply.ok(mapper.writeValueAsString(body)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private Map<String, String> query(URI uri) {
        var query = new HashMap<String, String>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return query;
    }
}
