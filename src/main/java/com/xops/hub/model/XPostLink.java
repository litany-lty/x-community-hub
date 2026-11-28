package com.xops.hub.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A link to one particular post on X. Display handles can change, post IDs cannot. */
public record XPostLink(String tweetId, String username) {
    private static final String POST_URL = "https?://(?:(?:www|mobile)\\.)?(?:x|twitter)\\.com/"
            + "(?:([a-z0-9_]{1,15})/status|i/web/status)/([0-9]{2,20})";
    private static final Pattern LINK = Pattern.compile(POST_URL + "(?:/(?:photo|video)/[0-9]+)?/?(?:[?#][^\\s]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERMALINK = Pattern.compile(POST_URL + "/?(?:[?#][^\\s]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_TEXT = Pattern.compile("(?<![a-z0-9_%/@?&#=.+-])" + POST_URL
            + "(?:/(?:photo|video)/[0-9]+)?/?(?![a-z0-9_/%-]|\\.[a-z0-9])"
            + "(?:[?#][a-z0-9%._~!$&'()*+,;=:@/?#-]*[a-z0-9%_~=&+#/-])?", Pattern.CASE_INSENSITIVE);

    public static XPostLink forMaterial(String materialId, String username, String sourceUrl) {
        XPostLink link = parse(sourceUrl);
        String id = materialId != null && materialId.matches("[0-9]{2,20}") ? materialId : link == null ? null : link.tweetId();
        if (id == null) return null;
        if (link != null && id.equals(link.tweetId()) && link.username() != null && !"i".equalsIgnoreCase(link.username())) return link;
        return new XPostLink(id, username == null ? null : username.strip().replaceFirst("^@+", ""));
    }

    public static XPostLink parse(String url) {
        return parse(url, LINK);
    }

    public static XPostLink parsePermalink(String url) {
        return parse(url, PERMALINK);
    }

    private static XPostLink parse(String url, Pattern pattern) {
        if (url == null) return null;
        Matcher match = pattern.matcher(url.strip());
        return match.matches() ? new XPostLink(match.group(2), match.group(1)) : null;
    }

    public String url() {
        String handle = username != null && username.matches("[A-Za-z0-9_]{1,15}") ? username : "i";
        return "https://x.com/" + handle + "/status/" + tweetId;
    }

    public XPostLink findIn(String text) {
        if (text == null) return null;
        Matcher match = IN_TEXT.matcher(text);
        while (match.find()) if (tweetId.equals(match.group(2))) return new XPostLink(tweetId, match.group(1));
        return null;
    }

    public boolean appearsIn(String text) {
        return findIn(text) != null;
    }

    public String removeFrom(String text) {
        return IN_TEXT.matcher(text).replaceAll(match -> tweetId.equals(match.group(2)) ? "" : Matcher.quoteReplacement(match.group()));
    }
}
