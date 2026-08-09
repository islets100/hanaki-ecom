package com.hanaki.ecom.agent;

import java.text.Normalizer;
import java.util.Locale;

/** 对查询做“只消除表示差异、不改变业务语义”的确定性归一化。 */
public final class QueryNormalizer {
    private QueryNormalizer() {}

    /**
     * <p>NFKC 会统一全角字母/数字和兼容字符；连续空白折叠为一个普通空格；拉丁大小写使用
     * Locale.ROOT 统一。这里绝不删除否定词、数字、金额、型号、时间、地点、单位、状态或标点，
     * 因为“可以退款”和“不可以退款”、“iPhone 15”和“iPhone 16”必须形成不同缓存键。</p>
     *
     * <p>算法是幂等的：normalize(normalize(x)) 与 normalize(x) 完全相同，便于属性测试并避免
     * 不同调用层重复归一化后产生新的键。</p>
     */
    public static String normalize(String value) {
        String compatible = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(compatible.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < compatible.length();) {
            int codePoint = compatible.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) result.append(' ');
            result.appendCodePoint(codePoint);
            pendingSpace = false;
        }
        return result.toString();
    }
}
