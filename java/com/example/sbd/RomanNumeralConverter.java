package com.example.sbd;

import java.util.HashMap;
import java.util.Map;

public class RomanNumeralConverter {
    private static final Map<Character, Integer> romanToIntMap = new HashMap<Character, Integer>();

    static {
        romanToIntMap.put('I', 1);
        romanToIntMap.put('V', 5);
        romanToIntMap.put('X', 10);
        romanToIntMap.put('L', 50);
        romanToIntMap.put('C', 100);
        romanToIntMap.put('D', 500);
        romanToIntMap.put('M', 1000);
    }

    public static int romanToInt(String s) {
        int result = 0;
        int prevValue = 0;
        for (char c : s.toCharArray()) {
            int value = romanToIntMap.get(c);
            result += value;
            if (value > prevValue) {
                result -= 2 * prevValue;
            }
            prevValue = value;
        }
        return result;
    }
}