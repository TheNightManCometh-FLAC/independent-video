package com.christopher.strata;

import java.util.Locale;

final class MenuSearch {
    static boolean matches(String query,String content) {
        String text=content.toLowerCase(Locale.ROOT);
        for(String word:query.trim().toLowerCase(Locale.ROOT).split("\\s+"))if(!text.contains(word))return false;
        return true;
    }
}
