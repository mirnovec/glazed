package com.nnpg.glazed.utils.glazed;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

public final class StringUtils {

    // cyrillic/greek lookalikes, NFKD doesnt catch these.
    // shoutout to whoever names themselves with 4 different alphabets to dodge a name check
    private static final Map<Integer, Character> HOMOGLYPHS = buildHomoglyphs();

    private StringUtils() {}

    public static String convertUnicodeToAscii(String text) {
        if (text == null || text.isEmpty()) return "";

        // kills fullwidth and the fancy math letters people hide names with
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD);

        StringBuilder result = new StringBuilder(normalized.length());

        // code points not chars or emoji/math letters get split in half
        normalized.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.NON_SPACING_MARK) return;

            Character mapped = HOMOGLYPHS.get(cp);
            if (mapped != null) {
                result.append(mapped);
            } else {
                result.appendCodePoint(Character.toLowerCase(cp));
            }
        });

        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static Map<Integer, Character> buildHomoglyphs() {
        Map<Integer, Character> map = new java.util.HashMap<>();
        put(map, 'a', 'ᴀ', 'а', 'α', 'А', 'Α', 'ɑ');
        put(map, 'b', 'ʙ', 'в', 'β', 'В', 'Β', 'Ь', 'ь');
        put(map, 'c', 'ᴄ', 'с', 'С', 'ϲ', 'Ϲ');
        put(map, 'd', 'ᴅ', 'ԁ', 'Ԁ');
        put(map, 'e', 'ᴇ', 'е', 'ε', 'Е', 'Ε', 'є', 'Є');
        put(map, 'f', 'ꜰ', 'ք');
        put(map, 'g', 'ɢ', 'ɡ', 'ց');
        put(map, 'h', 'ʜ', 'н', 'Н', 'Η', 'һ', 'Һ');
        put(map, 'i', 'ɪ', 'і', 'І', 'Ι', 'ι', 'ǀ');
        put(map, 'j', 'ᴊ', 'ј', 'Ј', 'ϳ');
        put(map, 'k', 'ᴋ', 'к', 'κ', 'К', 'Κ');
        put(map, 'l', 'ʟ', 'ӏ', 'Ӏ');
        put(map, 'm', 'ᴍ', 'м', 'М', 'Μ');
        put(map, 'n', 'ɴ', 'п', 'η', 'П', 'Ν', 'ռ');
        put(map, 'o', 'ᴏ', 'о', 'ο', 'О', 'Ο', 'σ', 'ө', 'Ө');
        put(map, 'p', 'ᴘ', 'р', 'ρ', 'Р', 'Ρ');
        put(map, 'q', 'ꞯ', 'ǫ', 'գ');
        put(map, 'r', 'ʀ', 'г', 'Г', 'ѓ');
        put(map, 's', 'ꜱ', 'ѕ', 'Ѕ', 'ș');
        put(map, 't', 'ᴛ', 'т', 'τ', 'Т', 'Τ');
        put(map, 'u', 'ᴜ', 'υ', 'ц', 'Ц', 'ս');
        put(map, 'v', 'ᴠ', 'ν', 'ѵ', 'Ѵ');
        put(map, 'w', 'ᴡ', 'ω', 'ш', 'Ш', 'ա');
        put(map, 'x', 'х', 'χ', 'Х', 'Χ', '×');
        put(map, 'y', 'ʏ', 'у', 'Υ', 'У', 'γ', 'ү');
        put(map, 'z', 'ᴢ', 'Ζ', 'ζ');
        return Map.copyOf(map);
    }

    private static void put(Map<Integer, Character> map, char ascii, char... variants) {
        for (char variant : variants) map.put((int) variant, ascii);
    }
}
