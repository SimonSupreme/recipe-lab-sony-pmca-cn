package com.voxivoid.recipelab;

import static com.voxivoid.recipelab.Fixtures.indexOf;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The favourites list: how it is stored, how a mark toggles, and how the browser walks the Favourites group. */
class FavouritesTest {

    private static List<Integer> favs(String... names) {
        List<Integer> f = new ArrayList<Integer>();
        for (String n : names) f.add(indexOf(n));
        return f;
    }

    @Test void noRecipeNameContainsTheSeparator() {
        for (Recipes.Recipe r : Recipes.ALL) assertFalse(r.name.contains(Favourites.SEP), r.name);
    }

    @Test void storedByNameInMarkingOrder() {
        List<Integer> f = favs("柯达 Portra 400", "Velvia 鲜艳", "Acros 黑白");
        assertEquals("柯达 Portra 400|Velvia 鲜艳|Acros 黑白", Favourites.encode(f));
        assertEquals(f, Favourites.decode("柯达 Portra 400|Velvia 鲜艳|Acros 黑白"));
    }

    @Test void nothingStoredMeansNoFavourites() {
        assertTrue(Favourites.decode(null).isEmpty());
        assertTrue(Favourites.decode("").isEmpty());
        assertEquals("", Favourites.encode(new ArrayList<Integer>()));
    }

    @Test void unknownAndRepeatedNamesAreDroppedOnLoad() {
        assertEquals(favs("Velvia 鲜艳", "Acros 黑白"), Favourites.decode("Velvia 鲜艳|Kodak Portra 9000|Velvia 鲜艳||Acros 黑白"),
                "a recipe that left the table, a repeat and an empty field all vanish; the rest keep their order");
    }

    @Test void toggleAppendsThenRemoves() {
        List<Integer> f = favs("Velvia 鲜艳");
        int portra = indexOf("柯达 Portra 400");
        assertTrue(Favourites.toggle(f, portra));
        assertEquals(favs("Velvia 鲜艳", "柯达 Portra 400"), f, "a new mark goes to the end");
        assertFalse(Favourites.toggle(f, indexOf("Velvia 鲜艳")));
        assertEquals(favs("柯达 Portra 400"), f);
        assertFalse(Favourites.toggle(f, portra));
        assertTrue(f.isEmpty());
    }

    @Test void afterRemovalTheHighlightMovesToTheNextOneOrTheNewLast() {
        List<Integer> f = favs("Velvia 鲜艳", "Acros 黑白", "Provia 标准");
        Favourites.toggle(f, indexOf("Acros 黑白"));          // removed the middle one, position 1
        assertEquals(indexOf("Provia 标准"), Favourites.afterRemoval(f, 1));
        Favourites.toggle(f, indexOf("Provia 标准"));         // removed the last one, position 1
        assertEquals(indexOf("Velvia 鲜艳"), Favourites.afterRemoval(f, 1));
        Favourites.toggle(f, indexOf("Velvia 鲜艳"));
        assertEquals(-1, Favourites.afterRemoval(f, 0));
    }

    @Test void decodeReturnsAListTheCallerCanMarkInto() {
        // MainActivity keeps the decoded list and adds to it on the first mark -- an immutable one would throw there
        for (String stored : new String[] { null, "", "Velvia 鲜艳", "nope" }) {
            List<Integer> f = Favourites.decode(stored);
            Favourites.toggle(f, indexOf("Acros 黑白"));
            assertTrue(f.contains(indexOf("Acros 黑白")), "decode(" + stored + ") returned a list that cannot be added to");
        }
    }

    @Test void toggleMessage() {
        assertEquals("Velvia 鲜艳 已加入收藏夹", Favourites.toggleMessage("Velvia 鲜艳", true));
        assertEquals("Velvia 鲜艳 已移出收藏夹", Favourites.toggleMessage("Velvia 鲜艳", false));
    }

    @Test void favouritesSitFirstInTheBrandColumnAndTheColumnWraps() {
        int last = Recipes.GROUPS.length - 1;
        assertEquals(0, Favourites.nextGroup(Favourites.GROUP, +1), "down from Favourites is the first brand");
        assertEquals(Favourites.GROUP, Favourites.nextGroup(0, -1), "up from the first brand is Favourites");
        assertEquals(Favourites.GROUP, Favourites.nextGroup(last, +1), "down from the last brand wraps to Favourites");
        assertEquals(last, Favourites.nextGroup(Favourites.GROUP, -1));
        assertEquals(0, Favourites.groupRow(Favourites.GROUP));
        assertEquals(1, Favourites.groupRow(0));
        assertEquals("收藏夹", Favourites.groupName(Favourites.GROUP));
        assertEquals(Recipes.GROUPS[3], Favourites.groupName(3));
    }

    @Test void landingOnAGroupHighlightsItsFirstRecipe() {
        List<Integer> f = favs("Acros 黑白", "Velvia 鲜艳");
        assertEquals(indexOf("Acros 黑白"), Favourites.landing(Favourites.GROUP, f), "the first marked, not the first in the table");
        assertEquals(Recipes.GROUP_START[3], Favourites.landing(3, f));
        assertEquals(-1, Favourites.landing(Favourites.GROUP, new ArrayList<Integer>()), "nothing to land on: the highlight stays put");
    }

    @Test void nextWalksTheMarkingOrderAndWraps() {
        List<Integer> f = favs("Acros 黑白", "Velvia 鲜艳", "柯达金 200");
        assertEquals(indexOf("Velvia 鲜艳"), Favourites.next(f, indexOf("Acros 黑白"), +1));
        assertEquals(indexOf("Acros 黑白"), Favourites.next(f, indexOf("柯达金 200"), +1));
        assertEquals(indexOf("柯达金 200"), Favourites.next(f, indexOf("Acros 黑白"), -1));
        assertEquals(indexOf("Acros 黑白"), Favourites.next(f, indexOf("Provia 标准"), +1), "an unmarked recipe steps onto the first favourite");
        assertEquals(indexOf("Acros 黑白"), Favourites.next(f, indexOf("Provia 标准"), -1), "backwards too, rather than into the middle of the list");
        assertEquals(-1, Favourites.next(new ArrayList<Integer>(), 0, +1));
    }

    @Test void theBrowserOpensOnFavouritesWhenTheRecipeIsOne() {
        List<Integer> f = favs("Velvia 鲜艳");
        assertEquals(Favourites.GROUP, Favourites.openingGroup(f, indexOf("Velvia 鲜艳")));
        assertEquals(Recipes.ALL[indexOf("Acros 黑白")].group, Favourites.openingGroup(f, indexOf("Acros 黑白")));
        assertEquals(0, Favourites.openingGroup(new ArrayList<Integer>(), 0));
    }

    @Test void onlyANonEmptyGroupHasARecipeColumn() {
        List<Integer> f = favs("Velvia 鲜艳");
        assertTrue(Favourites.hasRecipes(Favourites.GROUP, f));
        assertFalse(Favourites.hasRecipes(Favourites.GROUP, new ArrayList<Integer>()));
        assertTrue(Favourites.hasRecipes(0, new ArrayList<Integer>()), "a brand always has recipes");
    }

    @Test void aGroupListsItsRecipesByPosition() {
        List<Integer> f = favs("Velvia 鲜艳", "Acros 黑白");
        assertEquals(2, Favourites.groupCount(Favourites.GROUP, f));
        assertEquals(Recipes.GROUP_COUNT[0], Favourites.groupCount(0, f));
        assertEquals(indexOf("Acros 黑白"), Favourites.recipeAt(Favourites.GROUP, 1, f));
        assertEquals(Recipes.GROUP_START[3] + 2, Favourites.recipeAt(3, 2, f));
        assertEquals(1, Favourites.positionIn(Favourites.GROUP, indexOf("Acros 黑白"), f));
        assertEquals(-1, Favourites.positionIn(Favourites.GROUP, indexOf("Provia 标准"), f));
        assertEquals(2, Favourites.positionIn(3, Recipes.GROUP_START[3] + 2, f));
        assertEquals(-1, Favourites.positionIn(3, Recipes.GROUP_START[0], f), "a recipe of another brand");
    }

    @Test void everyRecipeCanBeMarkedAndReadBack() {
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 0; i < Recipes.ALL.length; i++) all.add(i);
        assertEquals(all, Favourites.decode(Favourites.encode(all)));
        assertEquals(Arrays.asList(Recipes.ALL.length - 1, 0), Favourites.decode(Favourites.encode(Arrays.asList(Recipes.ALL.length - 1, 0))));
    }
}
