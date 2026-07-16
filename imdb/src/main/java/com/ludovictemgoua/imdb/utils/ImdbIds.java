package com.ludovictemgoua.imdb.utils;

public final class ImdbIds {

    private ImdbIds() {
    }

    public static int parseTitleId(String tt) {
        return Integer.parseInt(requirePrefix(tt, "tt"));
    }

    public static int parsePersonId(String nm) {
        return Integer.parseInt(requirePrefix(nm, "nm"));
    }

    public static String formatTitleId(int tconst) {
        return "tt" + pad7(tconst);
    }

    public static String formatPersonId(int nconst) {
        return "nm" + pad7(nconst);
    }

    private static String requirePrefix(String id, String prefix) {
        if (id == null || !id.startsWith(prefix) || id.length() <= prefix.length()) {
            throw new IllegalArgumentException("Expected an id starting with '" + prefix + "': " + id);
        }
        return id.substring(prefix.length());
    }

    private static String pad7(int value) {
        return String.format("%07d", value);
    }
}
