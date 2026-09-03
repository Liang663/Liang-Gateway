package com.liang.gateway.support;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

final class WindowsPathSanitizer {

    private WindowsPathSanitizer() {}

    static void stripQuotedPathEntries() {
        String path = System.getenv("PATH");
        if (path == null || !path.contains("\"")) {
            return;
        }
        String cleaned = Arrays.stream(path.split(";"))
                .map(part -> part.replace("\"", "").trim())
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(";"));
        if (!overrideProcessEnvironment("PATH", cleaned)) {
            overrideProcessEnvironment("Path", cleaned);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean overrideProcessEnvironment(String key, String value) {
        try {
            Class<?> processEnvironment = Class.forName("java.lang.ProcessEnvironment");
            Field unmodifiable = processEnvironment.getDeclaredField("theUnmodifiableEnvironment");
            unmodifiable.setAccessible(true);
            Object unmodifiableMap = unmodifiable.get(null);
            Field m = unmodifiableMap.getClass().getDeclaredField("m");
            m.setAccessible(true);
            ((Map<String, String>) m.get(unmodifiableMap)).put(key, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through to the case-insensitive map used on Windows.
        }
        try {
            Class<?> processEnvironment = Class.forName("java.lang.ProcessEnvironment");
            Field caseInsensitive = processEnvironment.getDeclaredField("theCaseInsensitiveEnvironment");
            caseInsensitive.setAccessible(true);
            ((Map<String, String>) caseInsensitive.get(null)).put(key, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
