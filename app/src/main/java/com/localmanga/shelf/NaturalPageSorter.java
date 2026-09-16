package com.localmanga.shelf;

import java.text.Collator;

final class NaturalPageSorter {
    private NaturalPageSorter() {}

    static int compare(String first, String second, Collator collator) {
        int firstIndex = 0;
        int secondIndex = 0;
        while (firstIndex < first.length() && secondIndex < second.length()) {
            char firstChar = first.charAt(firstIndex);
            char secondChar = second.charAt(secondIndex);
            if (Character.isDigit(firstChar) && Character.isDigit(secondChar)) {
                int firstEnd = digitRunEnd(first, firstIndex);
                int secondEnd = digitRunEnd(second, secondIndex);
                int firstSignificant = skipLeadingZeros(first, firstIndex, firstEnd);
                int secondSignificant = skipLeadingZeros(second, secondIndex, secondEnd);
                int firstLength = firstEnd - firstSignificant;
                int secondLength = secondEnd - secondSignificant;
                if (firstLength != secondLength) return Integer.compare(firstLength, secondLength);
                for (int offset = 0; offset < firstLength; offset++) {
                    int comparison = Character.compare(first.charAt(firstSignificant + offset), second.charAt(secondSignificant + offset));
                    if (comparison != 0) return comparison;
                }
                int firstRunLength = firstEnd - firstIndex;
                int secondRunLength = secondEnd - secondIndex;
                if (firstRunLength != secondRunLength) return Integer.compare(firstRunLength, secondRunLength);
                firstIndex = firstEnd;
                secondIndex = secondEnd;
            } else {
                int firstEnd = nonDigitRunEnd(first, firstIndex);
                int secondEnd = nonDigitRunEnd(second, secondIndex);
                int comparison = collator.compare(first.substring(firstIndex, firstEnd), second.substring(secondIndex, secondEnd));
                if (comparison != 0) return comparison;
                firstIndex = firstEnd;
                secondIndex = secondEnd;
            }
        }
        return Integer.compare(first.length(), second.length());
    }

    private static int digitRunEnd(String value, int start) {
        int end = start;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        return end;
    }

    private static int nonDigitRunEnd(String value, int start) {
        int end = start;
        while (end < value.length() && !Character.isDigit(value.charAt(end))) end++;
        return end;
    }

    private static int skipLeadingZeros(String value, int start, int end) {
        int index = start;
        while (index < end - 1 && value.charAt(index) == '0') index++;
        return index;
    }
}
