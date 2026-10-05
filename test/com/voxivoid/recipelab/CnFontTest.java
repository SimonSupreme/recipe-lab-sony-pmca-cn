package com.voxivoid.recipelab;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The no-tofu gate: every non-ASCII character that can reach the camera's screen must be a glyph of the
 * bundled assets/cn.ttf, because the camera's system font has Latin and punctuation but no CJK. The test
 * reads the font's real cmap (no fonttools needed -- a plain-Java TrueType cmap reader for formats 4 and
 * 12), collects every character the app can render, and refuses any character the font cannot draw.
 *
 * Adding a recipe or a UI string with a character the subset lacks fails here, not as a box on the LCD.
 * To fix: extend the subset with tools/font-subset.py.
 */
class CnFontTest {

    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"");

    /** the cmap of a TrueType font: format 4 (BMP) and format 12 (UCS-4) subtables are understood */
    private static List<Integer> fontChars(String path) throws IOException {
        byte[] b = Files.readAllBytes(Paths.get(path));
        int numTables = u16(b, 4);
        int cmapAt = -1;
        for (int i = 0; i < numTables; i++) {
            int rec = 12 + 16 * i;
            if (b[rec] == 'c' && b[rec + 1] == 'm' && b[rec + 2] == 'a' && b[rec + 3] == 'p') cmapAt = u32(b, rec + 8);
        }
        assertTrue(cmapAt >= 0, "no cmap table in " + path);

        int best = -1;
        int subtables = u16(b, cmapAt + 2);
        for (int i = 0; i < subtables; i++) {   // prefer a Windows UCS-4 table, else any Windows BMP one
            int rec = cmapAt + 4 + 8 * i;
            int platform = u16(b, rec), encoding = u16(b, rec + 2);
            int at = cmapAt + u32(b, rec + 4);   // the record's offset counts from the start of the cmap table
            if (platform == 3 && encoding == 10) { best = at; break; }
            if (platform == 3 && encoding == 1 && best < 0) best = at;
        }
        assertTrue(best >= 0, "no usable cmap subtable");

        List<Integer> chars = new ArrayList<Integer>();
        int format = u16(b, best);
        if (format == 12) {
            int groups = u32(b, best + 12);
            for (int g = 0; g < groups; g++) {
                int at = best + 16 + 12 * g, start = u32(b, at), end = u32(b, at + 4);
                for (int c = start; c <= end; c++) chars.add(c);
            }
        } else if (format == 4) {
            int segCount = u16(b, best + 6) / 2;
            int endAt = best + 14, startAt = endAt + 2 * segCount + 2, deltaAt = startAt + 2 * segCount, rangeAt = deltaAt + 2 * segCount;
            for (int s = 0; s < segCount; s++) {
                int start = u16(b, startAt + 2 * s), end = u16(b, endAt + 2 * s);
                for (int c = start; c <= end && c != 0xFFFF; c++) chars.add(c);
            }
        } else fail("unsupported cmap subtable format " + format);
        return chars;
    }

    private static int u16(byte[] b, int at) { return (b[at] & 0xff) << 8 | b[at + 1] & 0xff; }
    private static int u32(byte[] b, int at) { return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | b[at + 3] & 0xff; }

    /** every non-ASCII char the app can show: recipe names and tips, strings.xml, layouts, Java string literals */
    private static String neededChars() throws IOException {
        StringBuilder need = new StringBuilder();
        String seed = new String(Files.readAllBytes(Paths.get("res/raw/recipes.json")), StandardCharsets.UTF_8);
        Matcher names = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(seed);
        while (names.find()) need.append(names.group(1));
        Matcher tips = Pattern.compile("\"tip\"\\s*:\\s*\"([^\"]*)\"").matcher(seed);
        while (tips.find()) need.append(tips.group(1));
        need.append(new String(Files.readAllBytes(Paths.get("res/values/strings.xml")), StandardCharsets.UTF_8));
        need.append(new String(Files.readAllBytes(Paths.get("res/layout/main.xml")), StandardCharsets.UTF_8));
        for (String file : new String[] { "src/com/voxivoid/recipelab/MainActivity.java", "src/com/voxivoid/recipelab/Params.java",
                "src/com/voxivoid/recipelab/Recipes.java", "src/com/voxivoid/recipelab/RecipePack.java",
                "src/com/voxivoid/recipelab/Favourites.java", "src/com/voxivoid/recipelab/DevTools.java",
                "src/com/voxivoid/recipelab/Legend.java", "src/com/voxivoid/recipelab/HintBar.java",
                "src/com/voxivoid/recipelab/PickerView.java", "src/com/voxivoid/recipelab/PromptView.java",
                "src/com/voxivoid/recipelab/MenuView.java", "src/com/voxivoid/recipelab/StarView.java",
                "src/com/voxivoid/recipelab/PickerView.java" }) {
            Matcher m = LITERAL.matcher(new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8));
            while (m.find()) need.append(m.group(1));
        }
        return need.toString();
    }

    @Test void everyRenderableCharacterIsAGlyphOfTheBundledFont() throws IOException {
        String have = charsToString(fontChars("assets/cn.ttf"));
        String need = neededChars();
        StringBuilder missing = new StringBuilder();
        for (char c : need.toCharArray())
            if (cjk(c) && have.indexOf(c) < 0) missing.append(c);
        assertTrue(missing.length() == 0,
                "these characters would render as boxes on the camera -- run tools/font-subset.py: " + missing);
    }

    /**
     * Whether a character is the bundled font's responsibility: CJK ideographs, CJK symbols and punctuation
     * and full-width forms, which the camera's system font cannot draw. Latin, digits and general punctuation
     * (-- · → … ±) belong to the system font -- verified on the A7R II, where the shipped UI renders them.
     */
    private static boolean cjk(char c) {
        if (c == 0xFEFF) return false;   // the BOM sentinel literal, never displayed
        return (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    private static String charsToString(List<Integer> chars) {
        StringBuilder s = new StringBuilder(chars.size());
        for (int c : chars) if (c < 0x10000) s.append((char) c);
        return s.toString();
    }

    @Test void theFontCoversTheShippedSeed() throws IOException {
        // spot-check characters that only the custom pack introduced, so a silent font swap cannot pass the gate
        String have = charsToString(fontChars("assets/cn.ttf"));
        for (char c : "井柏然温俊国贸朦胧荫雾".toCharArray()) assertTrue(have.indexOf(c) >= 0, "missing " + c);
    }
}
