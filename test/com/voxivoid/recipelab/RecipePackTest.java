package com.voxivoid.recipelab;

import static com.voxivoid.recipelab.Fixtures.staged;
import static com.voxivoid.recipelab.Fixtures.w;
import static com.voxivoid.recipelab.Params.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The custom-recipe pack: the shipped seed must parse clean under every rule, and bad data -- a wrong range, a
 * typo'd field, a truncated file -- must come back as an error, never as a crash or a quietly wrong recipe.
 */
class RecipePackTest {

    /** the pack as it ships; tests read the real file so fixture and shipped data cannot drift apart */
    private static final String SEED = "recipes.json";

    private static String seed() throws Exception {
        return new String(Files.readAllBytes(Paths.get(SEED)), StandardCharsets.UTF_8);
    }

    /** one valid entry; tests splice a broken variant of one field into it */
    private static final String BASE = "{\"name\":\"测试\",\"style\":\"STANDARD\",\"contrast\":0,\"saturation\":0,"
            + "\"sharpness\":0,\"wbAmber\":0,\"wbGreen\":0,\"ev\":0,\"dro\":\"DRO_1\",\"pe\":0,\"flashSuggest\":\"OFF\"}";

    private static RecipePack.Result parse(String json) { return RecipePack.parse(json); }

    private static void bad(String json, String what) {
        RecipePack.Result r = parse(json);
        assertFalse(r.ok(), what + ": expected an error, got " + r.recipes.size() + " recipes and none");
        assertTrue(mentions(r, what), "no error mentions " + what + ": " + r.errors);
    }

    private static boolean mentions(RecipePack.Result r, String what) {
        for (String e : r.errors) if (e.contains(what)) return true;
        return false;
    }

    // ---- the shipped seed
    /** the generated 自定义 block must be exactly what recipes.json says, field by field: the generator's chain is only as trustworthy as this check */
    @Test void theStaticTableMatchesTheAuthoringJson() throws Exception {
        RecipePack.Result parsed = parse(seed());
        assertTrue(parsed.ok(), parsed.errors.toString());
        List<Recipes.Recipe> json = parsed.recipes;
        assertEquals(json.size(), Recipes.GROUP_COUNT[Recipes.CUSTOM], "the generated block and the JSON list the same recipes");
        int i = Recipes.GROUP_START[Recipes.CUSTOM];
        for (Recipes.Recipe r : json) {
            Recipes.Recipe t = Recipes.ALL[i++];
            assertEquals(r.name, t.name);
            assertEquals(r.style, t.style); assertEquals(r.sat, t.sat); assertEquals(r.con, t.con); assertEquals(r.sharp, t.sharp);
            assertEquals(r.wbMode, t.wbMode); assertEquals(r.kelvin, t.kelvin);
            assertEquals(r.ab, t.ab); assertEquals(r.gm, t.gm); assertEquals(r.ev, t.ev); assertEquals(r.dro, t.dro);
            assertEquals(0, t.matrix); assertEquals(0, t.pe); assertEquals(0, t.sub);
            assertEquals(r.flash, t.flash); assertEquals(r.tip, t.tip);
        }
    }

    @Test void theShippedSeedParsesClean() throws Exception {
        RecipePack.Result r = parse(seed());
        assertTrue(r.ok(), "the shipped seed must be valid: " + r.errors);
        assertEquals(19, r.recipes.size(), "the pack Simon authored");
    }

    @Test void seedRecipesLandInTheCustomGroup() throws Exception {
        for (Recipes.Recipe x : parse(seed()).recipes) {
            assertEquals(Recipes.CUSTOM, x.group, x.name);
            assertFalse(x.isEffect(), x.name + ": the pack schema forbids effects");
            assertEquals(0, x.matrix, x.name);
            assertTrue(x.wbMode == 0 || x.wbMode == Params.WB_AUTO || x.wbMode == Params.WB_KELVIN, x.name + ": wbMode " + x.wbMode);
            if (x.wbMode == Params.WB_KELVIN) {
                assertEquals(0, x.kelvin % 100, x.name + ": kelvin is stored in hundreds");
                assertTrue(x.kelvin / 100 >= Params.ROW_MIN[Params.R_KELVIN] && x.kelvin / 100 <= Params.ROW_MAX[Params.R_KELVIN], x.name + ": kelvin " + x.kelvin);
            } else assertEquals(0, x.kelvin, x.name + ": kelvin set without colour-temperature WB");
        }
    }

    @Test void seedValuesMatchTheAuthoredEntries() throws Exception {
        List<Recipes.Recipe> rs = parse(seed()).recipes;
        Recipes.Recipe first = rs.get(0);
        assertEquals("我的日常基准｜写实原生感", first.name);
        assertEquals(Recipes.STD, first.style);
        assertEquals(-1, first.con); assertEquals(0, first.sat); assertEquals(1, first.sharp);
        assertEquals(1, first.ab); assertEquals(0, first.gm); assertEquals(0, first.ev); assertEquals(1, first.dro);
        assertEquals(Recipes.FLASH_OFF, first.flash);
        assertEquals("日常自然光记录，不建议闪光", first.tip);

        Recipes.Recipe g12 = rs.get(4);
        assertEquals(1, g12.ev, "ev 0.3 is one step of 1/3 EV");
        assertEquals(Recipes.FLASH_ON, g12.flash);

        Recipes.Recipe cine = rs.get(18);
        assertEquals(Recipes.FLASH_SOFT, cine.flash);
        assertEquals(-1, cine.gm, "wbGreen -1 is magenta one step");
        assertEquals(Params.WB_KELVIN, cine.wbMode, "the 800T look locks tungsten");
        assertEquals(3200, cine.kelvin);
        assertEquals(0, cine.ab, "the locked kelvin carries the warmth; no amber shift on top");
    }

    // ---- kelvin lock: the tungsten-film look needs the base fixed, not an AWB guess
    @Test void kelvinLockParsesAndRoundsTheTemperature() {
        RecipePack.Result r = parse("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"KELVIN\",\"kelvin\":3200,\"wbGreen\":0") + "]");
        assertTrue(r.ok(), r.errors.toString());
        assertEquals(Params.WB_KELVIN, r.recipes.get(0).wbMode);
        assertEquals(3200, r.recipes.get(0).kelvin);
        r = parse("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"AUTO\",\"wbGreen\":0") + "]");
        assertTrue(r.ok(), r.errors.toString());
        assertEquals(Params.WB_AUTO, r.recipes.get(0).wbMode);
        assertEquals(0, r.recipes.get(0).kelvin);
    }

    @Test void kelvinRulesAreEnforced() {
        bad("[" + BASE.replace("\"wbGreen\":0", "\"kelvin\":3200,\"wbGreen\":0") + "]", "kelvin 需要");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"KELVIN\",\"wbGreen\":0") + "]", "缺 kelvin");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"KELVIN\",\"kelvin\":3250,\"wbGreen\":0") + "]", "100 的倍数");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"KELVIN\",\"kelvin\":2400,\"wbGreen\":0") + "]", "2500..9900");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"KELVIN\",\"kelvin\":10000,\"wbGreen\":0") + "]", "2500..9900");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"AUTO\",\"kelvin\":3200,\"wbGreen\":0") + "]", "AUTO 白平衡不能带 kelvin");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbMode\":\"SUNNY\",\"wbGreen\":0") + "]", "SUNNY");
    }

    /** the pack voluntarily stays inside the -3..+3 the camera menu offers -- document that, so a drift shows */
    @Test void theSeedStaysWithinTheMenuRange() throws Exception {
        for (Recipes.Recipe x : parse(seed()).recipes) {
            String n = x.name;
            assertTrue(x.sat >= -3 && x.sat <= 3, n + ": saturation " + x.sat);
            assertTrue(x.con >= -3 && x.con <= 3, n + ": contrast " + x.con);
            assertTrue(x.sharp >= -3 && x.sharp <= 3, n + ": sharpness " + x.sharp);
            assertTrue(x.ab >= -3 && x.ab <= 3, n + ": wbAmber " + x.ab);
            assertTrue(x.gm >= -3 && x.gm <= 3, n + ": wbGreen " + x.gm);
            assertTrue(x.ev >= -3 && x.ev <= 3, n + ": ev steps " + x.ev);
        }
    }

    // ---- ev: thirds, never a float
    @Test void evTokensParseToThirdsOfAStop() {
        assertEquals(0, RecipePack.evSteps("0"));
        assertEquals(1, RecipePack.evSteps("0.3"));
        assertEquals(2, RecipePack.evSteps("0.7"));
        assertEquals(3, RecipePack.evSteps("1.0"));
        assertEquals(3, RecipePack.evSteps("1"));
        assertEquals(-4, RecipePack.evSteps("-1.3"));
        assertEquals(15, RecipePack.evSteps("5.0"));
        assertEquals(-15, RecipePack.evSteps("-5.0"));
        assertEquals("0", RecipePack.evToken(0));
        assertEquals("0.3", RecipePack.evToken(1));
        assertEquals("-1.3", RecipePack.evToken(-4));
    }

    @Test void evTokensThatAreNotThirdsAreRejected() {
        for (String t : new String[] { "0.4", "0.33", "0.1", "5.1", "6", "01", "1.", "-.3", "1e3", "" })
            assertThrows(IllegalArgumentException.class, () -> RecipePack.evSteps(t), "ev " + t);
    }

    @Test void anEvErrorNamesTheToken() {
        bad("[" + BASE.replace("\"ev\":0", "\"ev\":0.4") + "]", "0.4");
    }

    // ---- ranges, per the camera's rows
    @Test void outOfRangeValuesAreNamed() {
        bad("[" + BASE.replace("\"contrast\":0", "\"contrast\":9") + "]", "contrast=9");
        bad("[" + BASE.replace("\"saturation\":0", "\"saturation\":17") + "]", "saturation=17");
        bad("[" + BASE.replace("\"sharpness\":0", "\"sharpness\":-9") + "]", "sharpness=-9");
        bad("[" + BASE.replace("\"wbAmber\":0", "\"wbAmber\":8") + "]", "wbAmber=8");
        bad("[" + BASE.replace("\"wbGreen\":0", "\"wbGreen\":-8") + "]", "wbGreen=-8");
    }

    @Test void aRangeErrorNamesTheBoundsItBroke() {
        RecipePack.Result r = parse("[" + BASE.replace("\"contrast\":0", "\"contrast\":99") + "]");
        assertTrue(mentions(r, "-8..8"), r.errors.toString());
    }

    @Test void everyOutOfRangeRecipeIsSkippedButTheOthersStillArrive() {
        String json = "[" + BASE.replace("\"测试\"", "\"甲\"") + ","
                + BASE.replace("\"测试\"", "\"乙\"").replace("\"contrast\":0", "\"contrast\":9") + ","
                + BASE.replace("\"测试\"", "\"丙\"") + "]";
        RecipePack.Result r = parse(json);
        assertEquals(2, r.recipes.size(), "the broken middle one is skipped, both neighbours arrive");
        assertEquals("甲", r.recipes.get(0).name);
        assertEquals("丙", r.recipes.get(1).name);
    }

    // ---- presence, type, unknown fields
    @Test void missingFieldsAreNamed() {
        for (String f : new String[] { "name", "style", "contrast", "saturation", "sharpness", "wbAmber", "wbGreen", "ev", "dro", "pe", "flashSuggest" }) {
            StringBuilder b = new StringBuilder("[{");
            String[] parts = BASE.substring(1, BASE.length() - 1).split(",(?=\")", -1);
            for (String p : parts) {
                if (p.startsWith("\"" + f + "\":")) continue;
                if (b.length() > 2) b.append(',');
                b.append(p);
            }
            bad(b.append("}]").toString(), f);
        }
        bad("[{}]", "name");   // and an empty object says so about the first thing it lacks
    }

    @Test void aMissingTipIsFineButPresentMustBeAText() {
        RecipePack.Result r = parse("[" + BASE + "]");   // BASE has no tip
        assertTrue(r.ok(), r.errors.toString());
        assertEquals("", r.recipes.get(0).tip);
        bad("[" + BASE.replace("\"pe\":0", "\"pe\":0,\"tip\":3") + "]", "tip");
    }

    @Test void unknownFieldsAreErrorsSoATypoCannotDropASetting() {
        bad("[" + BASE.replace("\"contrast\":0", "\"contrst\":0") + "]", "未知字段 contrst");
        bad("[" + BASE + ",{" + BASE.substring(1, BASE.length() - 1) + ",\"extra\":1}]", "未知字段 extra");
    }

    @Test void wrongTypesAreNamed() {
        bad("[" + BASE.replace("\"name\":\"测试\"", "\"name\":3") + "]", "name");
        bad("[" + BASE.replace("\"dro\":\"DRO_1\"", "\"dro\":1") + "]", "dro");
        bad("[" + BASE.replace("\"contrast\":0", "\"contrast\":\"0\"") + "]", "contrast");
        bad("[" + BASE.replace("\"contrast\":0", "\"contrast\":0.5") + "]", "contrast");   // only ev may carry a fraction
    }

    @Test void duplicatedFieldsReportAndDropTheWholeRecipe() {
        String json = "[" + BASE.replace("\"ev\":0", "\"ev\":0,\"ev\":1") + "," + BASE.replace("\"测试\"", "\"甲\"") + "]";
        RecipePack.Result r = parse(json);
        assertTrue(mentions(r, "字段重复"), r.errors.toString());
        assertEquals(1, r.recipes.size(), "the object with a repeated field is dropped, not half-trusted");
        assertEquals("甲", r.recipes.get(0).name);
    }

    // ---- names
    @Test void namesMustBeUniqueInThePackAndAgainstTheTable() {
        bad("[" + BASE + "," + BASE + "]", "重名");
        bad("[" + BASE.replace("\"测试\"", "\"柯达 Portra 400\"") + "]", "内置");
    }

    @Test void namesCannotCarryTheSeparatorOrGrowTooLong() {
        bad("[" + BASE.replace("\"测试\"", "\"测|试\"") + "]", "|");
        StringBuilder long1 = new StringBuilder();
        for (int i = 0; i < RecipePack.NAME_MAX + 1; i++) long1.append('字');
        bad("[" + BASE.replace("\"测试\"", "\"" + long1 + "\"") + "]", "超过");
    }

    // ---- enums
    @Test void styleDroAndFlashAreChecked() {
        bad("[" + BASE.replace("\"style\":\"STANDARD\"", "\"style\":\"SLOG\"") + "]", "SLOG");
        bad("[" + BASE.replace("\"dro\":\"DRO_1\"", "\"dro\":\"DRO_9\"") + "]", "DRO_9");
        bad("[" + BASE.replace("\"flashSuggest\":\"OFF\"", "\"flashSuggest\":\"MAYBE\"") + "]", "MAYBE");
        // the wider enums the schema does allow
        RecipePack.Result r = parse("[" + BASE.replace("\"dro\":\"DRO_1\"", "\"dro\":\"DRO_AUTO\"") + "]");
        assertTrue(r.ok(), r.errors.toString());
        assertEquals(Recipes.DRO_AUTO, r.recipes.get(0).dro);
        r = parse("[" + BASE.replace("\"style\":\"STANDARD\"", "\"style\":\"red-leaves\"") + "]");
        assertTrue(r.ok(), r.errors.toString());
        assertEquals(Recipes.AUTUMN, r.recipes.get(0).style, "the schema takes the runtime key in any case");
    }

    @Test void anEffectRecipeIsNotPackable() {
        bad("[" + BASE.replace("\"pe\":0", "\"pe\":4") + "]", "特效");
    }

    // ---- syntax: the parse must stop and say where, never run off the end
    @Test void syntaxErrorsCarryThePosition() {
        for (String json : new String[] { "", "[", "[{", "[" + BASE, "[" + BASE + ",", "[] x", "[}" }) {
            RecipePack.Result r = parse(json);
            assertFalse(r.ok(), "this must not parse: " + json);
            assertTrue(mentions(r, "语法错误"), r.errors.toString());
        }
    }

    @Test void unclosedStringsAndRawControlCharactersAreSyntaxErrors() {
        RecipePack.Result r = parse("[{\"name\":\"测");
        assertTrue(mentions(r, "未闭合"), r.errors.toString());
        r = parse("[{\"name\":\"测\t试\"}]");   // a raw tab inside a string
        assertTrue(mentions(r, "控制字符"), r.errors.toString());
    }

    @Test void numbersKeepToTheJsonShape() {
        bad("[{" + BASE.substring(1, BASE.length() - 1).replace("\"contrast\":0", "\"contrast\":01") + "}]", "前导零");
        RecipePack.Result r = parse("[{" + BASE.substring(1, BASE.length() - 1).replace("\"contrast\":0", "\"contrast\":-0") + "}]");
        assertTrue(r.ok(), "-0 is legal JSON and zero: " + r.errors);
    }

    @Test void escapesSurviveTheRoundTrip() {
        RecipePack.Result r = parse("[" + BASE.replace("\"测试\"", "\"测\\\"试\\\\\"") + "]");
        assertTrue(r.ok(), r.errors.toString());
        assertEquals("测\"试\\", r.recipes.get(0).name);
    }

    // ---- limits and the empty pack
    @Test void anEmptyPackIsLegalAndCarriesNothing() {
        RecipePack.Result r = parse("[]");
        assertTrue(r.ok(), r.errors.toString());
        assertTrue(r.recipes.isEmpty());
    }

    @Test void nullAndOversizedInputAreRejected() {
        assertTrue(parse(null).errors.size() == 1);
        StringBuilder huge = new StringBuilder("[");
        for (int i = 0; i <= RecipePack.MAX_CHARS; i++) huge.append(' ');
        huge.append(']');
        assertTrue(mentions(parse(huge.toString()), "超过"));
    }

    @Test void tooManyRecipesIsAnErrorNotALoop() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i <= RecipePack.MAX_RECIPES; i++) {
            if (i > 0) b.append(',');
            b.append(BASE.replace("\"测试\"", "\"测试" + i + "\""));
        }
        assertTrue(mentions(parse(b.append(']').toString()), "超过"));
    }

    // ---- encode
    @Test void encodeRoundTripsTheShippedSeed() throws Exception {
        List<Recipes.Recipe> a = parse(seed()).recipes;
        List<Recipes.Recipe> b = parse(RecipePack.encode(a)).recipes;
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) same(a.get(i), b.get(i));
    }

    @Test void encodeRefusesWhatTheSchemaCannotSay() {
        List<Recipes.Recipe> withEffect = new ArrayList<Recipes.Recipe>();
        withEffect.add(new Recipes.Recipe(0, "x", Recipes.STD, 0, 0, 0, 0, 0, 0, 0, 0, Recipes.PE_RETRO, 0, Recipes.DRO_AUTO));
        assertThrows(IllegalArgumentException.class, () -> RecipePack.encode(withEffect));
        List<Recipes.Recipe> withoutFlash = new ArrayList<Recipes.Recipe>();
        withoutFlash.add(new Recipes.Recipe(0, "x", Recipes.STD, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Recipes.DRO_OFF, 0, Recipes.FLASH_NONE, ""));
        assertThrows(IllegalArgumentException.class, () -> RecipePack.encode(withoutFlash), "flashName would return null and write broken JSON");
        List<Recipes.Recipe> kelvinWithoutMode = new ArrayList<Recipes.Recipe>();
        kelvinWithoutMode.add(new Recipes.Recipe(0, "x", Recipes.STD, 0, 0, 0, 0, 0, 3200, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RecipePack.encode(kelvinWithoutMode));
    }

    @Test void kelvinLockSurvivesTheEncodeRoundTrip() {
        List<Recipes.Recipe> one = new ArrayList<Recipes.Recipe>();
        one.add(new Recipes.Recipe(Recipes.CUSTOM, "夜景", Recipes.STD, 0, -1, 0, 0, Params.WB_KELVIN, 3200, 0, -1, 0, 0, 1, 0, Recipes.FLASH_SOFT, ""));
        RecipePack.Result back = parse(RecipePack.encode(one));
        assertTrue(back.ok(), back.errors.toString());
        Recipes.Recipe r = back.recipes.get(0);
        assertEquals(Params.WB_KELVIN, r.wbMode);
        assertEquals(3200, r.kelvin);
    }

    // ------------------------------------------------------------ the whole camera path, simulated
    // The unit tests above prove the bytes the app would SEND. These mirror, for the custom pack, the store
    // simulations the built-in table goes through (ParamsWritesTest): stage, write into a fake settings store,
    // read the store back -- on the camera, what survives that loop is what survives a power cycle.

    private static List<Recipes.Recipe> pack() throws Exception {
        RecipePack.Result r = parse(seed());
        assertTrue(r.ok(), r.errors.toString());
        return r.recipes;
    }

    /** every custom recipe over every quality base: what is stored is exactly what reads back */
    @Test void everyCustomRecipeReadsBackAsItWasStored() throws Exception {
        for (int base : new int[] { Q_RAW, Q_RAWJPG, Q_FINE, Q_STD }) {
            for (Recipes.Recipe r : pack()) {
                Map<Integer, Integer> store = Fixtures.factoryStore();
                int[] cur = Fixtures.load(store);
                cur[Params.R_QUAL] = base;
                int[] edit = staged(r, cur, base);
                Fixtures.apply(store, writes(cur, edit, Fixtures.storedSub(store, edit)));
                store.put(ID_QFMT, Q_FMT_CODE[edit[Params.R_QUAL]]);
                store.put(ID_QJPG, Q_JPG_CODE[edit[Params.R_QUAL]]);
                assertArrayEquals(edit, Fixtures.load(store), r.name + " over " + Q_LABEL[base] + ": stored is not what reads back");
            }
        }
    }

    /** the pack walked in order on one camera, then back to the factory look: no stale byte anywhere */
    @Test void storingThePackInOrderNeverLeavesAStaleByte() throws Exception {
        List<Recipes.Recipe> rs = pack();
        Map<Integer, Integer> store = Fixtures.factoryStore();
        for (int i = 0; i <= rs.size(); i++) {
            Recipes.Recipe r = i < rs.size() ? rs.get(i) : Recipes.ALL[0];   // last step: the factory look
            int[] cur = Fixtures.load(store);
            int[] edit = staged(r, cur, Q_FINE);
            Fixtures.apply(store, writes(cur, edit, Fixtures.storedSub(store, edit)));
            assertEquals(0, Params.dirtyRows(Fixtures.load(store), edit, Fixtures.storedSub(store, edit)), r.name + " still dirty after storing");
        }
        int[] expected = Fixtures.factoryRows();
        expected[Params.R_KELVIN] = 32;   // an AWB recipe leaves the dial at the last Kelvin recipe's 3200K
        assertArrayEquals(expected, Fixtures.load(store), "back at the factory look");
    }

    /** the locked-temperature recipe writes the mode, the temperature and the per-mode fine-tune pair */
    @Test void theKelvinLockWritesTemperatureAndItsOwnFineTunePair() throws Exception {
        Recipes.Recipe cine = pack().get(18);
        int[] cur = Fixtures.factoryRows();
        List<Write> ws = writes(cur, staged(cine, cur, Q_FINE), 0);
        assertTrue(ws.contains(w(ID_WB_MODE, WB_KELVIN)), ws.toString());
        assertTrue(ws.contains(w(ID_WB_TEMP, 32)), "3200K is stored in hundreds: " + ws);
        assertTrue(ws.contains(w(ID_WB_AB_K, 0)), ws.toString());
        assertTrue(ws.contains(w(ID_WB_GM_K, 1)), "wbGreen -1 is magenta-positive in the store: " + ws);
    }

    /** nothing the pack writes ever leaves the set of slots the app checks before writing */
    @Test void thePackWritesOnlySlotsTheAppKnows() throws Exception {
        Set<Integer> checked = new HashSet<Integer>(allSlots());
        int[] cur = Fixtures.factoryRows();
        for (Recipes.Recipe r : pack())
            for (Write wr : writes(cur, staged(r, cur, Q_FINE), 0))
                assertTrue(checked.contains(wr.id), String.format("%08x is written by %s but never checked", wr.id, r.name));
    }

    /** every custom recipe drives a complete live-preview parameter set, like the built-in table must */
    @Test void everyCustomRecipePreviewsACompleteParameterSet() throws Exception {
        for (Recipes.Recipe r : pack()) {
            Map<String, String> p = preview(staged(r, Fixtures.factoryRows(), Q_FINE));
            for (String k : new String[] { "color-mode", "saturation", "contrast", "sharpness", "rgb-matrix-mode", "whitebalance",
                    "light-balance-for-white-balance", "color-compensation-for-white-balance", "storage-fmt", "jpeg-quality",
                    "picture-effect", "exposure-compensation", "dro-mode" })
                assertNotNull(p.get(k), r.name + " sets no " + k);
        }
    }

    // ------------------------------------------------------------ the two tables together
    // The built-in table and the pack will live on one camera, switched between without order. The layer
    // separation itself is also a contract: a built-in never carries pack-only data, and encode refuses it.

    /** every built-in stays pack-clean: no flash advice, no tip -- those belong to the custom layer only */
    @Test void builtInsCarryNoPackData() {
        for (Recipes.Recipe r : Recipes.ALL) {
            if (r.group == Recipes.CUSTOM) continue;   // the custom group is the pack itself
            assertEquals(Recipes.FLASH_NONE, r.flash, r.name);
            assertEquals("", r.tip, r.name);
        }
    }

    /** encode is for the pack: a built-in would lose data; a custom recipe round-trips */
    @Test void encodeRefusesTheBuiltInsAndRoundTripsTheCustoms() {
        List<Recipes.Recipe> customs = new ArrayList<Recipes.Recipe>();
        for (Recipes.Recipe r : Recipes.ALL) {
            List<Recipes.Recipe> one = new ArrayList<Recipes.Recipe>();
            one.add(r);
            if (r.group == Recipes.CUSTOM) customs.add(r);
            else assertThrows(IllegalArgumentException.class, () -> RecipePack.encode(one), r.name);
        }
        RecipePack.Result back = parse(RecipePack.encode(customs));
        assertTrue(back.ok(), back.errors.toString());
        assertEquals(customs.size(), back.recipes.size());
    }

    /**
     * The whole camera, both tables: built-ins and customs interleaved on one store, every recipe stored and
     * read back equal, no step leaves a stale byte, and the factory look comes back clean at the end. The
     * interleaving is the point -- AWB built-ins and the kelvin-locked custom fight over the same WB slots.
     */
    @Test void bothTablesInterleavedOnOneCameraLeaveNoStaleByte() throws Exception {
        List<Recipes.Recipe> customs = pack();
        Map<Integer, Integer> store = Fixtures.factoryStore();
        List<Recipes.Recipe> order = new ArrayList<Recipes.Recipe>();
        for (int i = 0; i < Recipes.ALL.length; i++) {
            order.add(Recipes.ALL[i]);
            if (i < customs.size()) order.add(customs.get(i));   // built-in i, then custom i
        }
        order.add(customs.get(customs.size() - 1));              // end on the kelvin lock
        order.add(Recipes.ALL[0]);                               // then the factory look
        for (Recipes.Recipe r : order) {
            int[] cur = Fixtures.load(store);
            int[] edit = staged(r, cur, Q_FINE);
            Fixtures.apply(store, writes(cur, edit, Fixtures.storedSub(store, edit)));
            assertArrayEquals(edit, Fixtures.load(store), r.name + ": stored is not what reads back");
            assertEquals(0, Params.dirtyRows(Fixtures.load(store), edit, Fixtures.storedSub(store, edit)), r.name + " still dirty");
        }
        int[] expected = Fixtures.factoryRows();
        expected[Params.R_KELVIN] = 32;   // the dial keeps the last kelvin recipe's 3200K; the factory look says "auto"
        assertArrayEquals(expected, Fixtures.load(store), "back at the factory look after 97 stores");
    }

    // ------------------------------------------------------------ bytes and encoding
    // The file ships inside the APK; if its bytes are not clean UTF-8 the camera shows mojibake. Tested at the
    // byte level, not the String level, so a wrong save-encoding fails here instead of on the LCD.

    @Test void theSeedFileIsCleanUtf8WithNoBom() throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(SEED));
        assertFalse(raw.length >= 3 && (raw[0] & 0xff) == 0xEF && (raw[1] & 0xff) == 0xBB && (raw[2] & 0xff) == 0xBF,
                "the seed carries a UTF-8 BOM; the camera side must not need one");
        java.nio.charset.CharsetDecoder strict = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        strict.decode(java.nio.ByteBuffer.wrap(raw));   // throws on any malformed sequence
        assertArrayEquals(raw, new String(raw, "UTF-8").getBytes("UTF-8"), "decode/encode round trip must be stable");
        assertFalse(new String(raw, "UTF-8").contains("﻿"), "a replacement char means the file was mangled");
    }

    @Test void aBomIsToleratedNotFatal() {
        RecipePack.Result r = parse("﻿" + "[" + BASE + "]");
        assertTrue(r.ok(), "an editor-added BOM must not break the parse: " + r.errors);
        assertEquals(1, r.recipes.size());
    }

    @Test void mojibakeAndSurrogatesAreRejectedAtAuthoringTime() {
        bad("[" + BASE.replace("\"测试\"", "\"测�\"") + "]", "无法显示");
        bad("[" + BASE.replace("\"测试\"", "\"测\\ud83d\\ude00\"") + "]", "无法显示");
        bad("[" + BASE + ",{" + BASE.substring(1, BASE.length() - 1) + ",\"tip\":\"好�\"}]", "无法显示");
    }

    private static void same(Recipes.Recipe a, Recipes.Recipe b) {
        assertEquals(a.name, b.name);
        assertEquals(a.style, b.style); assertEquals(a.sat, b.sat); assertEquals(a.con, b.con); assertEquals(a.sharp, b.sharp);
        assertEquals(a.ab, b.ab); assertEquals(a.gm, b.gm); assertEquals(a.ev, b.ev); assertEquals(a.dro, b.dro);
        assertEquals(a.wbMode, b.wbMode); assertEquals(a.kelvin, b.kelvin);
        assertEquals(a.flash, b.flash); assertEquals(a.tip, b.tip);
    }
}
