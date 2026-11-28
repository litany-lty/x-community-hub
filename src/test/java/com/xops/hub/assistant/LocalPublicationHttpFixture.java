package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.xops.hub.config.XOpsProperties;
import com.xops.hub.controller.ApiExceptionHandler;
import com.xops.hub.controller.ScheduledTweetController;
import com.xops.hub.controller.XOAuthController;
import com.xops.hub.model.AnalyticsReportModels.AuthenticatedXUser;
import com.xops.hub.provider.SharedApiExecutor;
import com.xops.hub.provider.XPublishClient;
import com.xops.hub.service.ScheduledTweetService;
import com.xops.hub.service.XOAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.xops.hub.assistant.AssistantStore.object;
import static org.mockito.Mockito.*;

abstract class LocalPublicationHttpFixture {
    static final String ACCOUNT = "501";
    static final String DRAFT = "historical-draft";
    static final String BASE = "/api/content-assistant";
    static final String EDIT = BASE + "/drafts/" + DRAFT;
    static final String QUEUE = "/api/scheduled-tweets";
    static final String A = "10101", B = "20202";
    static final String FINAL_TEXT = "本次冰川考察已核对现场读数。";

    @TempDir Path runtime;
    final ObjectMapper json = new ObjectMapper();
    final MockHttpSession session = new MockHttpSession();
    final List<JsonNode> captured = new CopyOnWriteArrayList<>();
    final MutableClock clock = new MutableClock();
    HttpServer upstream;
    SharedApiExecutor workers;
    ScheduledTweetService scheduler;
    AssistantStore store;
    MockMvc mvc;
    XOAuthService oauth;
    XPublishClient client;
    SourceCollector collector;
    AssistantController drafts;
    volatile boolean restrictQuotes;
    volatile boolean failNextPost;
    long due;

    static final class MutableClock extends Clock {
        Instant now = Instant.now();
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }

    @BeforeEach void assemble() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/2/tweets", exchange -> {
            try (exchange) {
                captured.add(json.readTree(exchange.getRequestBody()));
                boolean refuse = restrictQuotes && captured.size() <= 2;
                byte[] response = json.writeValueAsBytes(refuse
                        ? object().put("detail", "You can only reply to or quote posts where you are mentioned or are the author")
                        : object().set("data", object().put("id", "909010")));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                int status = failNextPost ? 503 : refuse ? 403 : 201;
                failNextPost = false;
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        upstream.start();
        var props = new XOpsProperties();
        props.setDiskCacheDir(runtime.toString());
        props.setTimezone("Asia/Shanghai");
        props.setSchedulerEnabled(false);
        props.setOfficialProxyUrl("");
        props.setOfficialBaseUrl("http://127.0.0.1:" + upstream.getAddress().getPort() + "/2");
        oauth = mock(XOAuthService.class);
        when(oauth.getTokenCapability(ACCOUNT)).thenReturn(new XOAuthService.TokenCapability(true, true, true));
        when(oauth.getValidWriteAccessToken(eq(ACCOUNT), anyBoolean())).thenReturn("local-fixture");
        workers = new SharedApiExecutor(props);
        client = new XPublishClient(props, json, workers);
        store = new AssistantStore(runtime.resolve("assistant"), json);
        scheduler = new ScheduledTweetService(props, json, oauth, client, clock);
        scheduler.setAssistantStore(store);
        var publishing = new AssistantPublishing(store, scheduler, oauth, client);
        collector = mock(SourceCollector.class);
        drafts = new AssistantController(store, collector, mock(AssistantWorker.class), publishing, json);
        var queue = new ScheduledTweetController(scheduler, json);
        ReflectionTestUtils.invokeMethod(queue, "setAssistantPublishing", publishing);
        mvc = MockMvcBuilders.standaloneSetup(drafts, queue).setControllerAdvice(new ApiExceptionHandler()).build();
        session.setAttribute(XOAuthController.SESSION_AUTH_USER,
                new AuthenticatedXUser(ACCOUNT, "elidlicom", "Fixture", "", false, clock.instant()));
        store.tx(tx -> tx.put("meta", "owner", object().put("accountId", ACCOUNT).put("username", "elidlicom").put("bindingSource", "EXPLICIT")));
        due = clock.millis() + 60_000;
        scheduler.processDueJobsForAccount(ACCOUNT);
    }

    @AfterEach void close() {
        if (scheduler != null) scheduler.shutdown();
        if (workers != null) workers.shutdown();
        if (upstream != null) upstream.stop(0);
    }
}
