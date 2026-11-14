package com.xops.hub.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HandleNormalizer {

    private static final Pattern URL_HANDLE_PATTERN = Pattern.compile(
            "(?:https?://)?(?:(?:www|mobile)\\.)?(?:x\\.com|twitter\\.com)/@?([A-Za-z0-9_]{1,15})(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RAW_HANDLE_PATTERN = Pattern.compile("^@?([A-Za-z0-9_]{1,15})$");

    private HandleNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("请输入有效的 X 账号（如 @demo_creator 或主页链接）");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("请输入有效的 X 账号（如 @demo_creator 或主页链接）");
        }

        Matcher urlMatcher = URL_HANDLE_PATTERN.matcher(trimmed);
        if (urlMatcher.matches()) {
            String handle = urlMatcher.group(1);
            validateNotReservedPath(handle);
            return handle;
        }

        Matcher rawMatcher = RAW_HANDLE_PATTERN.matcher(trimmed);
        if (rawMatcher.matches()) {
            return rawMatcher.group(1);
        }

        throw new IllegalArgumentException("X 账号格式无效：仅支持 1-15 位字母、数字、下划线或 x.com 主页链接");
    }

    public static String normalizeKey(String input) {
        return normalize(input).toLowerCase(Locale.ROOT);
    }

    private static void validateNotReservedPath(String handle) {
        String lower = handle.toLowerCase(Locale.ROOT);
        if ("home".equals(lower) || "explore".equals(lower) || "notifications".equals(lower)
                || "messages".equals(lower) || "settings".equals(lower) || "search".equals(lower)
                || "i".equals(lower) || "intent".equals(lower) || "compose".equals(lower)
                || "hashtag".equals(lower) || "login".equals(lower) || "logout".equals(lower)
                || "tos".equals(lower) || "privacy".equals(lower)) {
            throw new IllegalArgumentException("检测到的是 X 系统页面链接，请输入具体的个人账号链接或 @用户名");
        }
    }
}
