package com.xops.hub;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSourceProviderTest {

    @Test
    void textPageKeepsOriginalAndQuoteTextWhileExcludingRepliesRetweetsAndMediaOnlyPosts() throws Exception {
        try (var fixture = new ExactFollowProviderTest().new Fixture(uri -> {
            if (uri.getPath().endsWith("/info")) {
                return ExactFollowProviderTest.Reply.ok("{\"data\":{\"id\":\"author-id\",\"userName\":\"author\"}}");
            }
            if (uri.getPath().endsWith("/last_tweets")) {
                return ExactFollowProviderTest.Reply.ok("""
                        {"tweets":[
                          {"id":"post","text":"author view","createdAt":"2026-11-21T01:00:00Z","quoted_tweet":{"text":"quoted fact","author":{"id":"quoted-id","userName":"quoted"}}},
                          {"id":"article","text":"preview","createdAt":"2026-11-21T00:00:00Z","article":{"title":"Full title","content":{"text":"Full article"}}},
                          {"id":"qarticle","text":"commentary","createdAt":"2026-11-20T23:30:00Z","quoted_tweet":{"id":"q-original","text":"article preview","author":{"id":"quoted-id","userName":"quoted"},"article":{"title":"Quoted title"}}},
                          {"id":"reply","text":"reply","isReply":true,"inReplyToUsername":"peer","createdAt":"2026-11-20T23:00:00Z"},
                          {"id":"retweet","text":"RT @peer repost","createdAt":"2026-11-20T22:00:00Z"},
                          {"id":"media","text":"","createdAt":"2026-11-20T21:00:00Z","media":[{"type":"video"}]}
                        ],"has_next_page":true,"next_cursor":"older"}
                        """);
            }
            if (uri.getPath().endsWith("/mentions")) {
                return ExactFollowProviderTest.Reply.ok("""
                        {"tweets":[{"id":"mention","text":"@author hello","createdAt":"2026-11-21T02:00:00Z","author":{"id":"peer-id","userName":"peer"}}],"has_next_page":false}
                        """);
            }
            if (uri.getPath().endsWith("/article")) {
                assertThat(uri.getQuery()).contains("tweet_id=q-original");
                return ExactFollowProviderTest.Reply.ok("""
                        {"status":"success","article":{"title":"Quoted title","preview_text":"preview only","contents":[{"type":"unstyled","text":"Quoted full body"}]}}
                        """);
            }
            if (uri.getPath().endsWith("/tweets")) {
                return ExactFollowProviderTest.Reply.ok("""
                        {"tweets":[
                          {"id":"post","author":{"id":"author-id","userName":"author"}},
                          {"id":"article","author":{"id":"author-id","userName":"author"}},
                          {"id":"qarticle","author":{"id":"author-id","userName":"author"}},
                          {"id":"reply","author":{"id":"author-id","userName":"author"}},
                          {"id":"retweet","author":{"id":"author-id","userName":"author"}},
                          {"id":"media","author":{"id":"author-id","userName":"author"}}
                        ]}
                        """);
            }
            throw new AssertionError(uri);
        })) {
            var page = fixture.provider.fetchTextPostsPage("author", "author-id", "", 100,
                    Instant.now().plusSeconds(3));
            assertThat(page.nextCursor()).isEqualTo("older");
            assertThat(page.exhausted()).isFalse();
            assertThat(page.posts()).extracting(post -> post.id())
                    .containsExactly("post", "article", "qarticle", "media");
            assertThat(page.posts().get(0).quotedText()).isEqualTo("quoted fact");
            assertThat(page.posts().get(0).textStatus()).isEqualTo("QUOTE_SOURCE_MISSING");
            assertThat(page.posts().get(0).styleText()).isEqualTo("author view");
            assertThat(page.posts().get(0).authorTextStatus()).isEqualTo("FULL");
            assertThat(page.posts().get(0).quotedTextStatus()).isEqualTo("SOURCE_MISSING");
            assertThat(page.posts().get(1).articleText()).contains("Full article").doesNotContain("Full title");
            assertThat(page.posts().get(1).styleText()).isEqualTo("Full article");
            assertThat(page.posts().get(2).articleText()).isEqualTo("Quoted full body");
            assertThat(page.posts().get(2).articleRole()).isEqualTo("QUOTED");
            assertThat(page.posts().get(2).articleTextStatus()).isEqualTo("FULL");
            assertThat(page.posts().get(2).styleText()).isEqualTo("commentary");
            assertThat(page.posts().get(3).textStatus()).isEqualTo("MEDIA_ONLY");
            var inbound = fixture.provider.fetchInboundTextPage("author", "author-id", "", 100,
                    Instant.now().plusSeconds(3));
            assertThat(inbound.exhausted()).isTrue();
            assertThat(inbound.interactions()).singleElement().satisfies(interaction -> {
                assertThat(interaction.id()).isEqualTo("mention");
                assertThat(interaction.peerId()).isEqualTo("peer-id");
            });
        }
    }
}
