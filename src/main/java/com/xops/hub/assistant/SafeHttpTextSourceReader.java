package com.xops.hub.assistant;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Reads bounded public text without cookies, credentials, script execution, OCR, or media analysis. */
public final class SafeHttpTextSourceReader implements TextSourceReader {
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_READ_RETRIES = 2;
    private static final int HTML_LIMIT = 2 * 1024 * 1024;
    private static final int PDF_LIMIT = 10 * 1024 * 1024;
    private static final java.util.regex.Pattern NON_TEXT_BLOCKS = java.util.regex.Pattern.compile(
            "(?is)<(script|style|noscript|template|svg)\\b[^>]*>.*?</\\1>");
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"assistant-source-timeout");t.setDaemon(true);return t;});

    public static final String DOH_URL_ENV = "X_CONTENT_ASSISTANT_DOH_URL";
    private static final java.util.Set<String> TRUSTED_DOH_ENDPOINTS = java.util.Set.of(
            "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query");
    private final String dohUrl;

    public SafeHttpTextSourceReader() {
        this("");
    }

    SafeHttpTextSourceReader(String dohUrl) {
        this.dohUrl = dohUrl == null ? "" : dohUrl.strip();
        if (!this.dohUrl.isBlank() && !TRUSTED_DOH_ENDPOINTS.contains(this.dohUrl))
            throw new IllegalArgumentException("Unsupported content assistant HTTPS DNS endpoint");
    }

    @Override
    public int maxConcurrency() {
        return 2;
    }

    @Override
    public ReadResult read(URI requested) {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        ReadResult lastFailure = null;
        for (int attempt = 0; attempt <= MAX_READ_RETRIES; attempt++) {
            AttemptOutcome outcome = readOnce(requested, end);
            if (!outcome.retryable() || attempt == MAX_READ_RETRIES) return outcome.result();
            lastFailure = outcome.result();
            long waitMillis = Math.max(250L * (1L << attempt), outcome.retryAfterMillis());
            long remainingMillis = TimeUnitMillis.fromNanos(end - System.nanoTime());
            if (remainingMillis <= waitMillis + 400L) return lastFailure;
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return failed(requested, requested, "InterruptedException");
            }
        }
        return lastFailure != null ? lastFailure : failed(requested, requested, "read retries exhausted");
    }

    private AttemptOutcome readOnce(URI requested, long end) {
        URI current=requested;
        try {
            for(int redirect=0;redirect<=MAX_REDIRECTS;redirect++) {
                validateShape(current);
                // Pin the validated answers to this connection while retaining the original TLS hostname.
                var addresses=resolvePublic(current.getHost(), end);
                var resolver=new org.apache.hc.client5.http.DnsResolver() {
                    public InetAddress[] resolve(String host) { return addresses.clone(); }
                    public String resolveCanonicalHostname(String host) { return host; }
                };
                var manager=org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver).setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                                .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(5)).build()).build();
                var req=new org.apache.hc.client5.http.classic.methods.HttpGet(current);
                req.setHeader("Accept","text/html,text/plain,application/pdf");
                req.setHeader("Accept-Encoding","gzip, deflate, identity");
                req.setHeader("User-Agent","XCommunityHub-TextResearch/1.0");
                long remaining=end-System.nanoTime();
                if (remaining<=0) {
                    manager.close();
                    return new AttemptOutcome(failed(requested,current,"read deadline"), false, 0L);
                }
                var alarm=DEADLINES.schedule(req::cancel,remaining,java.util.concurrent.TimeUnit.NANOSECONDS);
                byte[] bytes;
                String contentType;
                try(var client=org.apache.hc.client5.http.impl.classic.HttpClients.custom().setConnectionManager(manager)
                        .disableCookieManagement().disableAutomaticRetries().disableRedirectHandling().disableContentCompression().build();
                    var response=client.execute(req)) {
                    int code=response.getCode();
                    if(code>=300 && code<400) {
                        var location=response.getFirstHeader("Location");
                        if(location==null || redirect==MAX_REDIRECTS) return new AttemptOutcome(failed(requested,current,"redirect limit or missing location"), false, 0L);
                        current=current.resolve(location.getValue());
                        continue;
                    }
                    if(code==401 || code==403) return new AttemptOutcome(new ReadResult(requested,current,"","","","",Instant.now(),Status.ACCESS_DENIED,"HTTP "+code), false, 0L);
                    if(code == 429 || code == 502 || code == 503 || code == 504) {
                        var retryHeader = response.getFirstHeader("Retry-After");
                        long retryAfterMs = parseRetryAfterMillis(retryHeader == null ? null : retryHeader.getValue());
                        return new AttemptOutcome(failed(requested,current,"HTTP "+code), true, retryAfterMs);
                    }
                    if(code<200 || code>=300) return new AttemptOutcome(failed(requested,current,"HTTP "+code), false, 0L);
                    var entity=response.getEntity();
                    if(entity==null) return new AttemptOutcome(failed(requested,current,"missing body"), false, 0L);
                    contentType=java.util.Objects.toString(entity.getContentType(),"").split(";",2)[0].strip().toLowerCase(Locale.ROOT);
                    String encoding = entity.getContentEncoding() == null ? "identity" : entity.getContentEncoding().strip().toLowerCase(Locale.ROOT);
                    if(!java.util.Set.of("application/pdf","text/html","text/plain","application/xhtml+xml").contains(contentType)
                            || !java.util.Set.of("identity","gzip","x-gzip","deflate").contains(encoding)) {
                        req.cancel();
                        return new AttemptOutcome(new ReadResult(requested,current,contentType,"","","",Instant.now(),Status.UNSUPPORTED_MEDIA,"unsupported type or encoding"), false, 0L);
                    }
                    int limit=contentType.equals("application/pdf")?PDF_LIMIT:HTML_LIMIT;
                    bytes=entity.getContentLength()>limit?null:readDecodedBounded(entity.getContent(),encoding,limit);
                    if(bytes==null) {
                        req.cancel();
                        return new AttemptOutcome(new ReadResult(requested,current,contentType,"","","",Instant.now(),Status.TOO_LARGE,"source exceeds limit"), false, 0L);
                    }
                } finally { alarm.cancel(false); }
                String text;
                String title = "";
                java.util.List<URI> visibleLinks = java.util.List.of();
                if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                    HtmlText html = parseHtml(new String(bytes, StandardCharsets.UTF_8), current);
                    text = html.text();
                    title = html.title();
                    visibleLinks = html.visibleLinks();
                } else if (contentType.contains("text/plain")) {
                    text = new String(bytes, StandardCharsets.UTF_8);
                } else if (contentType.contains("application/pdf")) {
                    text = extractPdfText(bytes);
                    if (text == null) return new AttemptOutcome(new ReadResult(requested, current, contentType, "", "", "", Instant.now(), Status.UNSUPPORTED_MEDIA, "PDF text extractor unavailable or no text layer"), false, 0L);
                } else {
                    return new AttemptOutcome(new ReadResult(requested, current, contentType, "", "", "", Instant.now(), Status.UNSUPPORTED_MEDIA, "unsupported content type"), false, 0L);
                }
                text = normalize(text);
                Status status = text.isBlank() ? Status.EMPTY_TEXT : Status.READ;
                return new AttemptOutcome(new ReadResult(requested, current, contentType, title, text, contentType.equals("application/pdf")?"PDF 文字层（页码见段落）":"网页正文段落", Instant.now(), status, "", visibleLinks), false, 0L);
            }
            return new AttemptOutcome(failed(requested, current, "redirect limit"), false, 0L);
        } catch (IllegalArgumentException nonPublicOrInvalid) {
            return new AttemptOutcome(failed(requested, current, nonPublicOrInvalid.getClass().getSimpleName()), false, 0L);
        } catch (IOException ioError) {
            return new AttemptOutcome(failed(requested, current, ioError.getClass().getSimpleName()), true, 0L);
        } catch (Exception e) {
            return new AttemptOutcome(failed(requested, current, e.getClass().getSimpleName()), false, 0L);
        }
    }

    private record AttemptOutcome(ReadResult result, boolean retryable, long retryAfterMillis) { }
    private static final class TimeUnitMillis {
        static long fromNanos(long nanos) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanos)); }
    }

    static long parseRetryAfterMillis(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return 0L;
        String trimmed = headerValue.strip();
        try {
            long seconds = Long.parseLong(trimmed);
            return Math.max(0L, Math.min(20_000L, seconds * 1000L));
        } catch (NumberFormatException ignored) {
            try {
                long epochMs = java.time.ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
                return Math.max(0L, Math.min(20_000L, epochMs - System.currentTimeMillis()));
            } catch (Exception unparseable) {
                return 0L;
            }
        }
    }

    static void validatePublicUri(URI uri) throws IOException { validateShape(uri); publicAddresses(uri.getHost()); }

    private static void validateShape(URI uri) {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("source URL must use http or https");
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null) throw new IllegalArgumentException("source URL cannot contain credentials and must have a host");
    }

    private static InetAddress[] publicAddresses(String host) throws IOException {
        return requirePublic(InetAddress.getAllByName(host));
    }

    private InetAddress[] resolvePublic(String host, long deadline) throws IOException {
        String literal = host.replace("[", "").replace("]", "");
        if (dohUrl.isBlank() || isLiteral(literal)) return publicAddresses(host);

        var answers = new java.util.LinkedHashSet<InetAddress>();
        try (var client = org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                .disableCookieManagement().disableAutomaticRetries().disableRedirectHandling()
                .disableContentCompression().build()) {
            for (int type : new int[] {1, 28}) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new UnknownHostException("HTTPS DNS deadline");
                String query = java.net.URLEncoder.encode(host, StandardCharsets.UTF_8);
                var request = new org.apache.hc.client5.http.classic.methods.HttpGet(dohUrl + "?name=" + query + "&type=" + type);
                request.setHeader("Accept", "application/dns-json");
                request.setHeader("Accept-Encoding", "identity");
                var alarm = DEADLINES.schedule(request::cancel, Math.min(remaining, Duration.ofSeconds(4).toNanos()),
                        java.util.concurrent.TimeUnit.NANOSECONDS);
                try (var response = client.execute(request)) {
                    if (response.getCode() != 200 || response.getEntity() == null)
                        throw new UnknownHostException("HTTPS DNS lookup failed");
                    byte[] bytes = readBounded(response.getEntity().getContent(), 64 * 1024);
                    if (bytes == null) {
                        request.cancel();
                        throw new UnknownHostException("HTTPS DNS response exceeds limit");
                    }
                    var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
                    if (result == null || result.path("Status").asInt(-1) != 0 || result.path("TC").asBoolean())
                        throw new UnknownHostException("HTTPS DNS lookup returned no reliable answer");
                    for (var answer : result.path("Answer")) {
                        int answerType = answer.path("type").asInt();
                        if (answerType != 1 && answerType != 28) continue;
                        String ip = answer.path("data").asText("");
                        if (!isLiteral(ip)) throw new UnknownHostException("HTTPS DNS returned an invalid address");
                        answers.add(InetAddress.getByName(ip));
                    }
                } finally {
                    alarm.cancel(false);
                }
            }
        }
        return requirePublic(answers.toArray(InetAddress[]::new));
    }

    private static boolean isLiteral(String address) {
        return org.apache.hc.core5.net.InetAddressUtils.isIPv4(address)
                || org.apache.hc.core5.net.InetAddressUtils.isIPv6(address);
    }

    private static InetAddress[] requirePublic(InetAddress[] addresses) throws UnknownHostException {
        if (addresses.length == 0) throw new UnknownHostException("source URL does not resolve to a public address");
        for(InetAddress address:addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("source URL resolves to a non-public address");
            }
            byte[] raw = address.getAddress();
            if(raw.length==16 && ((raw[0]&0xe0)!=0x20 || (raw[0]==0x20 && raw[1]==1 && (raw[2]&255)==13 && (raw[3]&255)==184)
                    || (raw[0]==0x20 && raw[1]==2) || (raw[0]==0x20 && raw[1]==1 && raw[2]==0 && raw[3]==0)))
                throw new IllegalArgumentException("non-public source address");
            if(raw.length==4 && ((raw[0]&255)==0 || (raw[0]&255)>=224
                    || (raw[0]&255)==198 && ((raw[1]&255)==18 || (raw[1]&255)==19)))
                throw new IllegalArgumentException("non-public source address");
            if (raw.length == 4 && (raw[0] & 0xff) == 100 && ((raw[1] & 0xff) >= 64 && (raw[1] & 0xff) <= 127)) {
                throw new IllegalArgumentException("source URL resolves to shared address space");
            }
        }
        return addresses;
    }

    static byte[] readDecodedBounded(InputStream rawInput, String encoding, int limit) throws IOException {
        byte[] rawBytes = readBounded(rawInput, limit);
        if (rawBytes == null) return null;
        if (encoding == null || encoding.isBlank() || "identity".equalsIgnoreCase(encoding)) {
            return rawBytes;
        }
        InputStream source = new java.io.ByteArrayInputStream(rawBytes);
        try (InputStream decoded = switch (encoding.toLowerCase(Locale.ROOT)) {
            case "gzip", "x-gzip" -> new java.util.zip.GZIPInputStream(source);
            case "deflate" -> new java.util.zip.InflaterInputStream(source);
            default -> throw new IOException("Unsupported content encoding: " + encoding);
        }) {
            return readBounded(decoded, limit);
        }
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        return bytes.length > limit ? null : bytes;
    }

    static HtmlText parseHtml(String html, URI page) throws IOException {
        String sanitized = NON_TEXT_BLOCKS.matcher(html == null ? "" : html).replaceAll(" ");
        StringBuilder text = new StringBuilder();
        StringBuilder title = new StringBuilder();
        var links = new java.util.LinkedHashSet<URI>();
        new ParserDelegator().parse(new StringReader(sanitized), new HTMLEditorKit.ParserCallback() {
            private boolean inTitle;
            private boolean inBody;
            private int ignoredDepth;
            private String href;

            private boolean isIgnoredTag(HTML.Tag tag) {
                if (tag == HTML.Tag.STYLE || tag == HTML.Tag.SCRIPT) return true;
                String name = tag == null ? "" : tag.toString().toLowerCase(Locale.ROOT);
                return java.util.Set.of("style", "script", "noscript", "template", "svg").contains(name);
            }

            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int pos) {
                if (tag == HTML.Tag.TITLE) inTitle = true;
                if (tag == HTML.Tag.BODY) inBody = true;
                if (isIgnoredTag(tag)) ignoredDepth++;
                if (inBody && ignoredDepth == 0 && tag == HTML.Tag.A)
                    href = java.util.Objects.toString(attributes.getAttribute(HTML.Attribute.HREF), "");
            }

            @Override public void handleEndTag(HTML.Tag tag, int pos) {
                if (tag == HTML.Tag.TITLE) inTitle = false;
                if (tag == HTML.Tag.BODY) inBody = false;
                if (isIgnoredTag(tag) && ignoredDepth > 0) ignoredDepth--;
                if (tag == HTML.Tag.A) href = null;
            }

            @Override public void handleText(char[] data, int pos) {
                if (inTitle) {
                    title.append(data);
                    return;
                }
                if (ignoredDepth > 0) return;
                text.append(data).append('\n');
                if (href == null || href.isBlank() || !inBody || links.size() >= 1000 || new String(data).isBlank()) return;
                try {
                    URI target = page.resolve(href.strip());
                    if (!AssistantEvidence.normalizedUrl(target.toString()).isBlank()) links.add(target);
                } catch (IllegalArgumentException invalid) {
                    // A malformed link has no attributable public target.
                }
            }
        }, true);
        return new HtmlText(normalize(title.toString()), normalize(text.toString()), java.util.List.copyOf(links));
    }

    private static String extractPdfText(byte[] bytes) throws IOException {
        try (var document=org.apache.pdfbox.Loader.loadPDF(bytes)) {
            if(document.isEncrypted() || document.getNumberOfPages()>300) return null;
            var buffer=new java.io.StringWriter();
            var capped=new java.io.FilterWriter(buffer) {
                private int remaining=HTML_LIMIT;
                @Override public void write(String str,int off,int len) throws IOException { check(len); super.write(str,off,len); }
                @Override public void write(char[] chars,int off,int len) throws IOException { check(len); super.write(chars,off,len); }
                private void check(int len) throws IOException { remaining-=len; if(remaining<0) throw new IOException("PDF text limit"); }
            };
            var stripper=new org.apache.pdfbox.text.PDFTextStripper() {
                private int lastPage;
                @Override protected void writeString(String text,java.util.List<org.apache.pdfbox.text.TextPosition> positions) throws IOException {
                    if(!text.isBlank() && lastPage!=getCurrentPageNo()) {
                        lastPage=getCurrentPageNo();
                        getOutput().write("\n[PDF 第 "+lastPage+" 页]\n");
                    }
                    super.writeString(text,positions);
                }
            };
            stripper.writeText(document,capped);
            return buffer.toString();
        }
    }

    private static String normalize(String value) { return value.replace('\u00a0', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim(); }
    private static ReadResult failed(URI requested, URI current, String detail) { return new ReadResult(requested, current, "", "", "", "", Instant.now(), Status.FAILED, detail); }
    record HtmlText(String title, String text, java.util.List<URI> visibleLinks) { }
}
