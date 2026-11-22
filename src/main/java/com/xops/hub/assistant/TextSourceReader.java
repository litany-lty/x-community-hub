package com.xops.hub.assistant;

import java.net.URI;
import java.time.Instant;
import java.util.List;

public interface TextSourceReader {
    ReadResult read(URI uri);

    default int maxConcurrency() {
        return 1;
    }

    enum Status { READ, UNSUPPORTED_MEDIA, ACCESS_DENIED, TOO_LARGE, EMPTY_TEXT, FAILED }
    record ReadResult(URI requestedUri, URI finalUri, String contentType, String title, String text,
                      String locator, Instant readAt, Status status, String detail, List<URI> visibleLinks) {
        public ReadResult { visibleLinks = visibleLinks == null ? List.of() : List.copyOf(visibleLinks); }

        public ReadResult(URI requestedUri, URI finalUri, String contentType, String title, String text,
                          String locator, Instant readAt, Status status, String detail) {
            this(requestedUri, finalUri, contentType, title, text, locator, readAt, status, detail, List.of());
        }
    }
}
