package com.greatwall.callring;

public final class NumberNormalizer {
    public static String normalize(String number) {
        if (number == null) return "";
        StringBuilder normalized = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char character = number.charAt(i);
            if (character == '-' || character == '(' || character == ')'
                    || Character.isWhitespace(character) || Character.isSpaceChar(character)) continue;
            normalized.append(character);
        }
        return normalized.toString();
    }
}
