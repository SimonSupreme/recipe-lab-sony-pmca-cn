package com.voxivoid.recipelab;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The composed table: install() appends the custom pack as the trailing 自定义 group, keeps the built-in
 * groups and every navigation rule intact, and restores the plain 77-recipe table with an empty pack --
 * which is also the degrade path when the user layer fails on the camera.
 */
class CustomTableTest {

    @AfterEach void restoreTheBuiltInTable() { Recipes.install(null); }

    private static List<Recipes.Recipe> seedPack() throws Exception {
        RecipePack.Result r = RecipePack.parse(new String(Files.readAllBytes(Paths.get("recipes.json")), StandardCharsets.UTF_8));
        assertTrue(r.ok(), r.errors.toString());
        return r.recipes;
    }

    /** the shipped table already carries the generated custom group */
    @Test void theGeneratedTableShipsTheCustomGroup() {
        assertEquals(96, Recipes.ALL.length);
        assertEquals(13, Recipes.GROUPS.length);
        assertEquals(Recipes.CUSTOM_NAME, Recipes.GROUPS[Recipes.CUSTOM]);
        assertEquals(77, Recipes.GROUP_START[Recipes.CUSTOM]);
        assertEquals(19, Recipes.GROUP_COUNT[Recipes.CUSTOM]);
        for (int g = 0; g < 13; g++) {
            assertTrue(Recipes.GROUP_COUNT[g] > 0);
            assertEquals(g, Recipes.ALL[Recipes.GROUP_START[g]].group, "start of " + Recipes.GROUPS[g]);
        }
        int last = 0;
        for (Recipes.Recipe r : Recipes.ALL) { assertTrue(r.group >= last, r.name); last = r.group; }
    }

    /** the authoring seed's names are all in the table already: installing it changes nothing */
    @Test void installingTheSeedIsANoOpOnTheGeneratedTable() throws Exception {
        Recipes.install(seedPack());
        assertEquals(96, Recipes.ALL.length, "the seed's names are all in the table -- nothing to add");
        assertEquals(13, Recipes.GROUPS.length);
    }

    @Test void aNovelCustomRecipeStillAppendsForPhaseTwo() {
        List<Recipes.Recipe> pack = new ArrayList<Recipes.Recipe>();
        pack.add(new Recipes.Recipe(Recipes.CUSTOM, "新名字", Recipes.STD, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, Recipes.FLASH_OFF, ""));
        Recipes.install(pack);
        assertEquals(97, Recipes.ALL.length);
        assertEquals(13, Recipes.GROUPS.length);
        assertEquals(20, Recipes.GROUP_COUNT[Recipes.CUSTOM]);
    }

    @Test void anEmptyPackRestoresTheShippedTable() throws Exception {
        Recipes.install(seedPack());
        Recipes.install(null);
        assertEquals(96, Recipes.ALL.length);
        assertEquals(13, Recipes.GROUPS.length);
    }

    @Test void namesTheTableAlreadyHasAreSkippedNotOverwritten() {
        List<Recipes.Recipe> pack = new ArrayList<Recipes.Recipe>();
        pack.add(new Recipes.Recipe(Recipes.CUSTOM, "柯达 Portra 400", Recipes.STD, 9, 9, 9, 0, 0, 0, 9, 9, 0, 0, 1, 0, Recipes.FLASH_OFF, ""));
        Recipes.install(pack);
        assertEquals(96, Recipes.ALL.length, "a name the table already has is skipped");
        assertEquals(Recipes.PORTRAIT, Recipes.ALL[Fixtures.indexOf("柯达 Portra 400")].style, "the built-in copy is untouched");
    }

    @Test void recipesOutsideTheCustomGroupAreNotInstalled() {
        List<Recipes.Recipe> pack = new ArrayList<Recipes.Recipe>();
        pack.add(new Recipes.Recipe(0, "混入品牌组", Recipes.STD, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, Recipes.FLASH_OFF, ""));
        Recipes.install(pack);
        assertEquals(96, Recipes.ALL.length, "only the custom group may be appended");
    }

    @Test void favouritesResolveCustomRecipesByName() throws Exception {
        Recipes.install(seedPack());
        List<Integer> favs = Favourites.decode("我的日常基准｜写实原生感|IXUS130｜闪光冷粉白皮");
        assertEquals(2, favs.size());
        assertEquals(77, favs.get(0).intValue());
        assertEquals(83, favs.get(1).intValue());
        assertEquals("自定义", Recipes.GROUPS[Recipes.ALL[favs.get(1)].group]);
    }

    @Test void navigationSpansTheCustomGroup() throws Exception {
        Recipes.install(seedPack());
        assertEquals(77, Recipes.next(76, +1), "the wheel rolls from the last built-in into the customs");
        assertEquals(76, Recipes.next(77, -1));
        assertEquals(0, Recipes.next(95, +1), "wraps over the whole composed table");
        int start = Recipes.GROUP_START[Recipes.CUSTOM];
        assertEquals(start + 1, Recipes.nextInGroup(start, +1));
        assertEquals(start, Recipes.nextInGroup(start + Recipes.GROUP_COUNT[Recipes.CUSTOM] - 1, +1), "wraps inside the custom group");
        assertEquals(start, Recipes.nextGroupStart(Recipes.GROUP_START[11], +1), "the brand column steps into 自定义");
        assertEquals(Recipes.GROUP_START[0], Recipes.nextGroupStart(start, +1), "and out of it wraps back to 索尼");
    }

    @Test void aPersistedRecipeIndexKeepsClampingAgainstTheComposedTable() throws Exception {
        Recipes.install(seedPack());
        assertEquals(90, Math.max(0, Math.min(Recipes.ALL.length - 1, 90)));
        assertEquals(95, Math.max(0, Math.min(Recipes.ALL.length - 1, 500)), "an index from a larger table clamps to the new end");
        Recipes.install(null);
        assertEquals(90, Math.max(0, Math.min(Recipes.ALL.length - 1, 90)), "the shipped table already reaches the custom group");
    }
}
