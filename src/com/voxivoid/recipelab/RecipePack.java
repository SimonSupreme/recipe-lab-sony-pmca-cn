package com.voxivoid.recipelab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.voxivoid.recipelab.Params.R_AB;
import static com.voxivoid.recipelab.Params.R_CON;
import static com.voxivoid.recipelab.Params.R_EV;
import static com.voxivoid.recipelab.Params.R_GM;
import static com.voxivoid.recipelab.Params.R_SAT;
import static com.voxivoid.recipelab.Params.R_SHARP;

/**
 * The custom-recipe pack: the JSON a user authors on a PC -- res/raw/recipes.json as shipped, the same string
 * later kept in the app's preferences -- parsed, validated and encoded back. No camera and no Android in here,
 * so tools/test.sh runs it, which is what makes the "JSON 校验" a CI gate instead of an on-camera surprise.
 *
 * Schema (every field checked; an unknown field is an error, so a typo cannot silently drop a setting):
 *   name          required, unique in the pack and against the built-in table, 1..26 chars, no '|'
 *   style         required, one of the known Creative Style names (the runtime key uppercased, e.g. STANDARD)
 *   contrast      required integer, -8..8    (the menu shows -3..+3; the store accepts more -- Params rows)
 *   saturation    required integer, -16..16
 *   sharpness     required integer, -8..8
 *   wbAmber       required integer, -7..7, amber positive / blue negative
 *   wbGreen       required integer, -7..7, GREEN positive / magenta negative (the store keeps magenta positive)
 *   wbMode        optional, AUTO / KELVIN -- absent means the recipe leaves the camera's WB mode alone
 *   kelvin        colour temperature in kelvin (2500..9900, a multiple of 100); required with wbMode KELVIN,
 *                 not allowed without it. Locking kelvin is what a tungsten film look needs (CineStill 800T)
 *   ev            required, thirds of EV as 0 / 0.3 / 0.7 / 1.0 ... 5.0 (a bare integer means whole EV);
 *                 held as 1/3-EV steps -- never a float
 *   dro           required, DRO_OFF / DRO_1..DRO_5 / DRO_AUTO
 *   pe            required, must be 0 -- effect recipes are not packable
 *   flashSuggest  required, ON / OFF / SOFT_ONLY -- advice text only; the app has no flash setting to write
 *   tip           optional, 1..30 chars, the browser line under a recipe
 *
 * A recipe that fails a rule is reported and skipped, the rest still parse; a syntax error anywhere stops the
 * parse at that character. Everything accepted lands in Recipes.CUSTOM.
 */
final class RecipePack {
    private RecipePack() {}

    // generous bounds: the pack is authored data, but a corrupt string must never spin or eat memory
    static final int MAX_CHARS = 65536, MAX_RECIPES = 128, NAME_MAX = 26, TIP_MAX = 30, VALUE_MAX = 64;

    /** what parse() found: the recipes that passed every rule, and every rule the others broke */
    static final class Result {
        final List<Recipes.Recipe> recipes = new ArrayList<Recipes.Recipe>();
        final List<String> errors = new ArrayList<String>();
        boolean ok() { return errors.isEmpty(); }
    }

    static Result parse(String json) {
        Result out = new Result();
        if (json == null) { out.errors.add("JSON 为空（null）"); return out; }
        String s = json.trim();
        if (s.startsWith("﻿")) s = s.substring(1).trim();   // a Windows editor's UTF-8 BOM is not JSON
        if (s.length() > MAX_CHARS) { out.errors.add("JSON 超过 " + MAX_CHARS + " 个字符"); return out; }
        try { new Parser(s, out).run(); }
        catch (Syntax e) { out.errors.add("JSON 语法错误（第 " + (e.at + 1) + " 个字符起）：" + e.getMessage()); }
        return out;
    }

    // ------------------------------------------------------------ field codecs
    /** style name -> stored enum, -1 when the name is not a known style */
    static int styleOf(String name) {
        for (int v = 1; v < Recipes.STYLE_NAMES.length; v++) {
            String k = Recipes.STYLE_NAMES[v];
            if (k != null && name.toUpperCase().equals(k.toUpperCase())) return v;
        }
        return -1;
    }

    /** a stored style back to its schema name, null for one the table does not know */
    static String styleName(int v) {
        String k = v >= 1 && v < Recipes.STYLE_NAMES.length ? Recipes.STYLE_NAMES[v] : null;
        return k == null ? null : k.toUpperCase();
    }

    /** dro name -> row value (0 off, 1..5 level, 6 auto), -1 when unknown */
    static int droOf(String name) {
        if ("DRO_OFF".equals(name)) return Recipes.DRO_OFF;
        if ("DRO_AUTO".equals(name)) return Recipes.DRO_AUTO;
        if (name.length() == 5 && name.startsWith("DRO_")) {
            char c = name.charAt(4);
            return c >= '1' && c <= '5' ? c - '0' : -1;
        }
        return -1;
    }

    static String droName(int v) {
        return v == Recipes.DRO_OFF ? "DRO_OFF" : v == Recipes.DRO_AUTO ? "DRO_AUTO" : v >= 1 && v <= 5 ? "DRO_" + v : null;
    }

    /** flashSuggest name -> advice constant, -1 when unknown */
    static int flashOf(String name) {
        if ("ON".equals(name)) return Recipes.FLASH_ON;
        if ("OFF".equals(name)) return Recipes.FLASH_OFF;
        if ("SOFT_ONLY".equals(name)) return Recipes.FLASH_SOFT;
        return -1;
    }

    static String flashName(int v) {
        return v == Recipes.FLASH_ON ? "ON" : v == Recipes.FLASH_OFF ? "OFF" : v == Recipes.FLASH_SOFT ? "SOFT_ONLY" : null;
    }

    /**
     * An ev token ("0", "0.3", "-1.3", "5.0" ...) to 1/3-EV steps. The token is matched digit by digit, never
     * through a float, so a third can not arrive rounded. Throws on anything that is not a third in -15..15.
     */
    static int evSteps(String t) {
        boolean neg = t.startsWith("-");
        String d = neg ? t.substring(1) : t;
        int dot = d.indexOf('.');
        String ip = dot < 0 ? d : d.substring(0, dot);
        if (ip.isEmpty() || ip.length() > 2) throw ev(t);
        if (ip.length() > 1 && ip.charAt(0) == '0') throw ev(t);   // leading zeros are not JSON numbers
        int whole = 0;
        for (int k = 0; k < ip.length(); k++) {
            char c = ip.charAt(k);
            if (c < '0' || c > '9') throw ev(t);
            whole = whole * 10 + (c - '0');
        }
        int steps = whole * 3;
        if (dot >= 0) {
            String fp = d.substring(dot + 1);
            if (fp.length() != 1) throw ev(t);
            char c = fp.charAt(0);
            if (c == '3') steps += 1; else if (c == '7') steps += 2; else if (c != '0') throw ev(t);
        }
        steps = neg ? -steps : steps;
        if (steps < Params.ROW_MIN[R_EV] || steps > Params.ROW_MAX[R_EV]) throw ev(t);
        return steps;
    }

    /** steps back to the schema token; 0 stays "0" */
    static String evToken(int steps) {
        if (steps == 0) return "0";
        int a = Math.abs(steps), w = a / 3, f = a % 3;
        return (steps < 0 ? "-" : "") + w + (f == 1 ? ".3" : f == 2 ? ".7" : ".0");
    }

    private static IllegalArgumentException ev(String t) { return new IllegalArgumentException("ev=" + t + " 不是 1/3 EV 步进（0/±0.3/±0.7…±5.0）"); }

    // ------------------------------------------------------------ encode
    /**
     * Recipes back to the schema, in pack order. A recipe the schema cannot say (matrix, WB mode, an effect)
     * throws rather than quietly losing the difference -- those belong in the built-in table.
     */
    static String encode(List<Recipes.Recipe> rs) {
        StringBuilder b = new StringBuilder("[\n");
        for (int n = 0; n < rs.size(); n++) {
            Recipes.Recipe r = rs.get(n);
            if (!Recipes.styleKnown(r.style)) throw new IllegalArgumentException(r.name + ": 未知风格 " + r.style);
            if (r.flash == Recipes.FLASH_NONE)
                throw new IllegalArgumentException(r.name + ": 种子配方必须带闪光建议（flashSuggest 为 ON / OFF / SOFT_ONLY）");
            if (r.matrix != 0 || r.pe != 0 || r.sub != 0)
                throw new IllegalArgumentException(r.name + ": 种子表达不了矩阵/特效，这类配方留在内置表");
            if (r.kelvin != 0 && r.wbMode != Params.WB_KELVIN)
                throw new IllegalArgumentException(r.name + ": kelvin 只能配合 KELVIN 白平衡");
            if (r.wbMode == Params.WB_KELVIN && r.kelvin == 0)
                throw new IllegalArgumentException(r.name + ": KELVIN 白平衡必须给 kelvin");
            b.append("  {\n    \"name\": ").append(q(r.name))
             .append(",\n    \"style\": \"").append(styleName(r.style)).append('"')
             .append(",\n    \"contrast\": ").append(r.con)
             .append(",\n    \"saturation\": ").append(r.sat)
             .append(",\n    \"sharpness\": ").append(r.sharp)
             .append(",\n    \"wbAmber\": ").append(r.ab)
             .append(",\n    \"wbGreen\": ").append(r.gm)
             .append(",\n    \"ev\": ").append(evToken(r.ev));
            if (r.wbMode == Params.WB_KELVIN) b.append(",\n    \"wbMode\": \"KELVIN\"").append(",\n    \"kelvin\": ").append(r.kelvin);
            else if (r.wbMode == Params.WB_AUTO) b.append(",\n    \"wbMode\": \"AUTO\"");
            b.append(",\n    \"dro\": \"").append(droName(r.dro)).append('"')
             .append(",\n    \"pe\": 0")
             .append(",\n    \"flashSuggest\": \"").append(flashName(r.flash)).append('"');
            if (r.tip != null && !r.tip.isEmpty()) b.append(",\n    \"tip\": ").append(q(r.tip));
            b.append("\n  }").append(n + 1 < rs.size() ? "," : "").append('\n');
        }
        return b.append(']').toString();
    }

    /** a JSON string with its escapes */
    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------ parser
    /** where the parser stopped; the character index is reported to the author */
    private static final class Syntax extends Exception {
        final int at;
        Syntax(String msg, int at) { super(msg); this.at = at; }
    }

    private static final class Parser {
        private final String s;
        private int i = 0;
        private final Result out;
        private final Set<String> seen = new LinkedHashSet<String>();
        private int index = 0;   // which object in the pack, for messages

        Parser(String s, Result out) { this.s = s; this.out = out; }

        void run() throws Syntax {
            ws();
            expect('[');
            ws();
            if (peek() == ']') { i++; }   // an empty pack is legal: nothing imported, nothing lost
            else while (true) {
                ws();
                object();
                ws();
                if (peek() == ',') { i++; continue; }
                expect(']');
                break;
            }
            ws();
            if (i < s.length()) throw new Syntax("数组结束后还有内容", i);
        }

        /** one { ... } : pairs into string / number maps, then every rule checked against the schema */
        private void object() throws Syntax {
            final int at = index++;
            if (at >= MAX_RECIPES) throw new Syntax("配方超过 " + MAX_RECIPES + " 条", i);
            expect('{');
            Map<String, String> strs = new LinkedHashMap<String, String>(), nums = new LinkedHashMap<String, String>();
            boolean dup = false;
            ws();
            if (peek() == '}') i++;
            else while (true) {
                ws();
                if (peek() != '"') throw new Syntax("字段名必须是字符串", i);
                String key = string();
                ws();
                expect(':');
                ws();
                char c = peek();
                if (c == '"') {
                    String v = string();
                    if (strs.containsKey(key) || nums.containsKey(key)) { err(at, key, "字段重复"); dup = true; }
                    strs.put(key, v);
                } else if (c == '-' || (c >= '0' && c <= '9')) {
                    String v = number();
                    if (nums.containsKey(key) || strs.containsKey(key)) { err(at, key, "字段重复"); dup = true; }
                    nums.put(key, v);
                } else throw new Syntax("字段值必须是字符串或数字", i);
                ws();
                if (peek() == ',') { i++; continue; }
                expect('}');
                break;
            }
            if (dup) return;   // a repeated field means the object cannot be trusted; report it and drop the whole recipe
            validate(at, strs, nums);
        }

        /** the schema itself: presence, type, range, enum, length -- every broken rule reported, the recipe skipped */
        private void validate(int at, Map<String, String> strs, Map<String, String> nums) {
            List<String> bad = new ArrayList<String>();
            for (String k : strs.keySet()) if (!KNOWN.contains(k)) bad.add("未知字段 " + k);
            for (String k : nums.keySet()) if (!KNOWN.contains(k)) bad.add("未知字段 " + k);

            String name = strs.get("name");
            if (name == null) { if (nums.containsKey("name")) bad.add("name 必须是字符串"); bad.add("缺 name"); }
            else {
                if (name.trim().isEmpty()) bad.add("name 为空");
                if (name.length() > NAME_MAX) bad.add("name " + name.length() + " 字，超过 " + NAME_MAX);
                if (name.indexOf(Favourites.SEP.charAt(0)) >= 0) bad.add("name 不能含 |（收藏夹分隔符）");
                if (!displayable(name)) bad.add("name 含相机字体无法显示的字符（控制符/替换符/代理项）");
            }

            int style = 0;
            if (present(strs, nums, "style", bad)) {
                int v = styleOf(strs.get("style"));
                if (v < 0) bad.add("style \"" + strs.get("style") + "\" 不是已知风格");
                else style = v;
            }

            int sat = intField(nums, "saturation", R_SAT, bad), con = intField(nums, "contrast", R_CON, bad),
                sharp = intField(nums, "sharpness", R_SHARP, bad), ab = intField(nums, "wbAmber", R_AB, bad),
                gm = intField(nums, "wbGreen", R_GM, bad), ev = evField(nums, bad);

            int dro = 0;
            if (present(strs, nums, "dro", bad)) {
                int v = droOf(strs.get("dro"));
                if (v < 0) bad.add("dro \"" + strs.get("dro") + "\" 不是 DRO_OFF / DRO_1..5 / DRO_AUTO");
                else dro = v;
            }

            Integer pe = intRaw(nums, "pe", bad);
            if (pe != null && pe != 0) bad.add("pe=" + pe + "：特效配方暂不支持种子格式");

            // ---- white-balance mode: absent leaves the camera's own; KELVIN locks a colour temperature
            int wbMode = 0, kelvin = 0;
            String wm = strs.get("wbMode");
            if (nums.containsKey("wbMode")) bad.add("wbMode 必须是字符串");
            else if (wm != null && !"AUTO".equals(wm) && !"KELVIN".equals(wm)) bad.add("wbMode \"" + wm + "\" 不是 AUTO / KELVIN");
            else if ("KELVIN".equals(wm)) {
                wbMode = Params.WB_KELVIN;
                if (strs.containsKey("kelvin")) bad.add("kelvin 必须是整数");
                Integer k = intRaw(nums, "kelvin", bad);
                if (k != null) {
                    if (k % 100 != 0) bad.add("kelvin=" + k + " 必须是 100 的倍数");
                    else if (k / 100 < Params.ROW_MIN[Params.R_KELVIN] || k / 100 > Params.ROW_MAX[Params.R_KELVIN])
                        bad.add("kelvin=" + k + " 超出 2500..9900");
                    else kelvin = k;
                }
            }
            else if ("AUTO".equals(wm)) {
                wbMode = Params.WB_AUTO;
                if (strs.containsKey("kelvin") || nums.containsKey("kelvin")) bad.add("AUTO 白平衡不能带 kelvin");
            }
            else if (strs.containsKey("kelvin") || nums.containsKey("kelvin")) bad.add("kelvin 需要 wbMode 为 KELVIN");

            int flash = 0;
            if (present(strs, nums, "flashSuggest", bad)) {
                int v = flashOf(strs.get("flashSuggest"));
                if (v < 0) bad.add("flashSuggest \"" + strs.get("flashSuggest") + "\" 不是 ON / OFF / SOFT_ONLY");
                else flash = v;
            }

            String tip = strs.get("tip");
            if (nums.containsKey("tip")) bad.add("tip 必须是字符串");
            else if (tip != null) {
                if (tip.length() > TIP_MAX) bad.add("tip " + tip.length() + " 字，超过 " + TIP_MAX);
                if (!displayable(tip)) bad.add("tip 含相机字体无法显示的字符（控制符/替换符/代理项）");
            }

            if (name != null && !name.trim().isEmpty()) {
                if (seen.contains(name)) bad.add("与前面的配方重名");
                for (Recipes.Recipe r : Recipes.ALL) if (r.name.equals(name)) { bad.add("与内置配方重名（" + name + "）"); break; }
            }

            if (!bad.isEmpty()) { for (String b : bad) out.errors.add("第 " + (at + 1) + " 条：" + b); return; }
            seen.add(name);
            out.recipes.add(new Recipes.Recipe(Recipes.CUSTOM, name, style, sat, con, sharp,
                    0, wbMode, kelvin, ab, gm, 0, ev, dro, 0, flash, tip == null ? "" : tip));
        }

        /** whether a string field is present as a string; missing or numeric is reported into bad */
        private boolean present(Map<String, String> strs, Map<String, String> nums, String key, List<String> bad) {
            if (strs.containsKey(key)) return true;
            if (nums.containsKey(key)) bad.add(key + " 必须是字符串");
            else bad.add("缺 " + key);
            return false;
        }

        /**
         * Whether every char of a string can reach the camera's screen: control characters never render, the
         * Unicode replacement char means the file was saved in the wrong encoding, and surrogates (emoji, rare
         * scripts) have no glyph in the bundled CJK subset. Rejecting them here turns mojibake into an error at
         * authoring time instead of tofu on the LCD.
         */
        private static boolean displayable(String s) {
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c < 0x20 || c == '�' || (c >= '\uD800' && c <= '\uDFFF')) return false;
            }
            return true;
        }

        /** an integer field checked against its row's range; 0 when missing (the caller already reported it) */
        private int intField(Map<String, String> nums, String key, int row, List<String> bad) {
            Integer v = intRaw(nums, key, bad);
            if (v == null) return 0;
            int lo = Params.ROW_MIN[row], hi = Params.ROW_MAX[row];
            if (v < lo || v > hi) { bad.add(key + "=" + v + " 超出 " + lo + ".." + hi); return 0; }
            return v;
        }

        private Integer intRaw(Map<String, String> nums, String key, List<String> bad) {
            String t = nums.get(key);
            if (t == null) { bad.add("缺 " + key); return null; }
            if (t.indexOf('.') >= 0) { bad.add(key + " 必须是整数"); return null; }
            try { return Integer.valueOf(t); }
            catch (NumberFormatException e) { bad.add(key + " 不是合法整数"); return null; }
        }

        private int evField(Map<String, String> nums, List<String> bad) {
            String t = nums.get("ev");
            if (t == null) { bad.add("缺 ev"); return 0; }
            try { return evSteps(t); }
            catch (IllegalArgumentException e) { bad.add(e.getMessage()); return 0; }
        }

        private void err(int at, String key, String what) {
            out.errors.add("第 " + (at + 1) + " 条：" + key + " " + what);
        }

        // ---- tokens
        private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

        private void expect(char c) throws Syntax {
            if (peek() != c) throw new Syntax("应为 '" + c + "'，实为 '" + (peek() == '\0' ? "结尾" : String.valueOf(peek())) + "'", i);
            i++;
        }

        private void ws() { while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++; }

        /** a JSON string with every standard escape; raw control characters and runaway length are errors */
        private String string() throws Syntax {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new Syntax("字符串未闭合", i);
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (i >= s.length()) throw new Syntax("转义被截断", i);
                    char e = s.charAt(i++);
                    if (e == '"' || e == '\\' || e == '/') b.append(e);
                    else if (e == 'n') b.append('\n');
                    else if (e == 't') b.append('\t');
                    else if (e == 'r') b.append('\r');
                    else if (e == 'b') b.append('\b');
                    else if (e == 'f') b.append('\f');
                    else if (e == 'u') {
                        if (i + 4 > s.length()) throw new Syntax("\\u 转义被截断", i);
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int h = Character.digit(s.charAt(i++), 16);
                            if (h < 0) throw new Syntax("\\u 转义不是十六进制", i);
                            v = (v << 4) | h;
                        }
                        b.append((char) v);
                    } else throw new Syntax("非法转义 \\" + e, i);
                } else {
                    if (c < 0x20) throw new Syntax("字符串里有未转义的控制字符", i - 1);
                    b.append(c);
                }
                if (b.length() > VALUE_MAX) throw new Syntax("字符串超过 " + VALUE_MAX + " 字符", i);
            }
            return b.toString();
        }

        /** a JSON number token, kept as text: shape errors are syntax, meaning is checked per field later */
        private String number() throws Syntax {
            int start = i;
            if (peek() == '-') i++;
            int digits = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; digits++; }
            if (digits == 0) throw new Syntax("数字格式错误", start);
            if (peek() == '.') {
                i++;
                int f = 0;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; f++; }
                if (f == 0) throw new Syntax("小数点后没有数字", start);
            }
            if (i - start > VALUE_MAX) throw new Syntax("数字超过 " + VALUE_MAX + " 位", start);
            String t = s.substring(start, i), m = t.startsWith("-") ? t.substring(1) : t;
            if (m.length() > 1 && m.charAt(0) == '0' && m.charAt(1) != '.') throw new Syntax("数字不允许前导零", start);
            return t;
        }
    }

    /** the fields the schema knows */
    private static final Set<String> KNOWN = new LinkedHashSet<String>();
    static {
        String[] k = { "name", "style", "contrast", "saturation", "sharpness", "wbAmber", "wbGreen", "wbMode", "kelvin", "ev", "dro", "pe", "flashSuggest", "tip" };
        for (String f : k) KNOWN.add(f);
    }
}
