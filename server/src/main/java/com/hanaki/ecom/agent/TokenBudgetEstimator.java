package com.hanaki.ecom.agent;

import org.springframework.stereotype.Component;

/** 无厂商 tokenizer 时使用偏保守的中英混合估算，并提供不截断 UTF-16 代理对的裁剪。 */
@Component
public final class TokenBudgetEstimator {
    public int estimate(String value) {
        if (value == null || value.isEmpty()) return 0;
        double tokens = 0;
        int latinRun = 0;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) && cp < 128) latinRun++;
            else {
                if (latinRun > 0) { tokens += Math.ceil(latinRun / 3.5d); latinRun = 0; }
                if (isCjk(cp)) tokens += 1.05;
                else if (!Character.isWhitespace(cp)) tokens += 0.35;
            }
        }
        if (latinRun > 0) tokens += Math.ceil(latinRun / 3.5d);
        return Math.max(1, (int) Math.ceil(tokens));
    }

    public String truncate(String value, int maxTokens) {
        if (value == null || maxTokens <= 0) return "";
        if (estimate(value) <= maxTokens) return value;
        int low = 0, high = value.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (middle < value.length() && Character.isLowSurrogate(value.charAt(middle))) middle--;
            if (middle <= low) middle = Math.min(high, low + 1);
            if (estimate(value.substring(0, middle)) <= maxTokens) low = middle;
            else high = Math.max(0, middle - 1);
        }
        int end = Math.min(low, value.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, Math.max(0, end));
    }

    private boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }
}
