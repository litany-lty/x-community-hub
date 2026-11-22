package com.xops.hub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JavaNetModelTransport implements CommandCodeTextModelClient.Transport {
    private final ObjectMapper mapper;
    private final HttpClient client;

    public JavaNetModelTransport(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public CommandCodeTextModelClient.TransportResponse post(
            URI uri, Map<String, String> headers, JsonNode body, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            headers.forEach(builder::header);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) responseHeaders.put(name.toLowerCase(), values.get(0));
            });
            JsonNode responseBody;
            try { responseBody = mapper.readTree(response.body()); }
            catch (Exception invalidJson) { responseBody = mapper.createObjectNode().put("unparseable", true); }
            return new CommandCodeTextModelClient.TransportResponse(response.statusCode(), responseBody, responseHeaders);
        } catch (IOException e) {
            throw new CommandCodeTextModelClient.ModelCallException("model response is unknown after I/O failure", false, true, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommandCodeTextModelClient.ModelCallException("model call interrupted", false, true, null);
        }
    }
}
