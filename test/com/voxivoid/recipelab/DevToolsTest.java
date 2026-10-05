package com.voxivoid.recipelab;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The developer menu and the sample run: the rows, the settle delay, the progress lines and the manifest. */
class DevToolsTest {

    // ---- menu rows
    @Test void everyRowHasALabelAndADetailLine() {
        for (int r = 0; r < DevTools.ROWS; r++) {
            for (boolean snapshotTaken : new boolean[] { false, true }) {
                assertFalse(DevTools.rowLabel(r, snapshotTaken, DevTools.SETTLE_DEFAULT, false).isEmpty(), "row " + r);
                assertFalse(DevTools.rowDetail(r, snapshotTaken).isEmpty(), "row " + r);
            }
        }
    }

    @Test void theSnapshotRowSaysWhichHalfOfTheToolItWillRun() {
        assertEquals("设置快照", DevTools.rowLabel(DevTools.ROW_SNAPSHOT, false, 0, false));
        assertEquals("设置对比", DevTools.rowLabel(DevTools.ROW_SNAPSHOT, true, 0, false), "a snapshot is on disk, so the next press diffs against it");
    }

    @Test void theReadOnlyRowCountsTheSlotsItWillTest() {
        assertEquals("只读检查 —— 26 个插槽", DevTools.rowLabel(DevTools.ROW_LOCKS, false, 0, false));
        assertEquals(Params.allSlots().size(), 26, "the label counts the slots, so the slots are what it must count");
    }

    @Test void theSampleRowNamesTheWholeTable() {
        assertEquals("拍摄样片 —— 96 个配方", DevTools.rowLabel(DevTools.ROW_SAMPLES, false, 0, false));
        assertEquals(96, Recipes.ALL.length, "the label counts the table, so the table is what it must count");
    }

    @Test void theDelayRowShowsTheChosenDelay() {
        assertEquals("稳定延迟 —— 0.8 s", DevTools.rowLabel(DevTools.ROW_SETTLE, false, 0, false));
        assertEquals("稳定延迟 —— 1.2 s", DevTools.rowLabel(DevTools.ROW_SETTLE, false, 1, false));
    }

    @Test void theRawModeRowSaysWhatItDoesToTheRecipe() {
        assertEquals("RAW 模式 DRO 关闭 —— 关", DevTools.rowLabel(DevTools.ROW_RAWMODE, false, 0, false));
        assertEquals("RAW 模式 DRO 关闭 —— 开", DevTools.rowLabel(DevTools.ROW_RAWMODE, false, 0, true));
        assertFalse(DevTools.rowDetail(DevTools.ROW_RAWMODE, false).isEmpty());
    }

    @Test void oneTurnOfTheMenuVisitsEveryRowItDefines() {
        // ROWS is what the menu can reach: a row defined past it is dead, and nothing else would say so
        Set<Integer> visited = new HashSet<Integer>();
        int r = DevTools.ROW_SNAPSHOT;
        for (int i = 0; i < DevTools.ROWS; i++) { visited.add(r); r = DevTools.nextRow(r, +1); }
        assertEquals(new HashSet<Integer>(Arrays.asList(DevTools.ROW_SNAPSHOT, DevTools.ROW_LOCKS, DevTools.ROW_SAMPLES, DevTools.ROW_SETTLE, DevTools.ROW_RAWMODE)), visited);
        assertEquals(DevTools.ROW_SNAPSHOT, r, "and comes back to the first row");
    }

    @Test void rowsWrapInBothDirections() {
        assertEquals(1, DevTools.nextRow(0, +1));
        assertEquals(0, DevTools.nextRow(DevTools.ROWS - 1, +1));
        assertEquals(DevTools.ROWS - 1, DevTools.nextRow(0, -1));
    }

    // ---- the settle delay
    @Test void delaysAreOrderedAndLabelledToOneDecimal() {
        for (int i = 1; i < DevTools.SETTLE_MS.length; i++) assertTrue(DevTools.SETTLE_MS[i] > DevTools.SETTLE_MS[i - 1], "delay " + i);
        assertEquals("2.0 s", DevTools.settleLabel(2));
        assertEquals("5.0 s", DevTools.settleLabel(DevTools.SETTLE_MS.length - 1));
    }

    @Test void aStoredDelayFromAnotherBuildFallsBackToTheDefault() {
        assertEquals(DevTools.SETTLE_DEFAULT, DevTools.clampSettle(-1));
        assertEquals(DevTools.SETTLE_DEFAULT, DevTools.clampSettle(DevTools.SETTLE_MS.length));
        assertEquals(2, DevTools.clampSettle(2));
    }

    @Test void theDelayCyclesThroughTheTable() {
        int idx = 0;
        for (int i = 0; i < DevTools.SETTLE_MS.length; i++) idx = DevTools.nextSettle(idx, +1);
        assertEquals(0, idx, "one turn through every delay comes back to the first");
        assertEquals(DevTools.SETTLE_MS.length - 1, DevTools.nextSettle(0, -1));
        assertEquals(DevTools.SETTLE_DEFAULT + 1, DevTools.nextSettle(DevTools.SETTLE_MS.length, +1), "an out-of-table index falls back to the default before it steps");
    }

    // ---- what the run says while it walks the table
    @Test void progressCountsFramesFromOneAndNamesTheWayOut() {
        String p = DevTools.progress(1, 77, "出厂默认（标准）");
        assertTrue(p.startsWith("拍摄 1 / 77"), p);
        assertTrue(p.contains("出厂默认（标准）"), p);
        assertTrue(p.contains("MENU"), p);
    }

    @Test void theEndOfARunSaysHowManyFramesAndWhereTheListIs() {
        assertTrue(DevTools.doneMessage(77, 77).contains("77 / 77"), DevTools.doneMessage(77, 77));
        assertTrue(DevTools.doneMessage(77, 77).contains(DevTools.MANIFEST));
        assertTrue(DevTools.stoppedMessage(12, 77).contains("12 / 77"), DevTools.stoppedMessage(12, 77));
        assertTrue(DevTools.stoppedMessage(0, 77).contains("第一张之前"), "nothing was shot, so there is nothing to point at");
        assertFalse(DevTools.stoppedMessage(0, 77).contains(DevTools.MANIFEST));
        assertTrue(DevTools.shootFailed(13, 12, "timeout").contains("第 13 张"), DevTools.shootFailed(13, 12, "timeout"));
    }

    // ---- the manifest
    @Test void theHeaderRecordsTheFrameCountTheOrderAndTheDelay() {
        String h = DevTools.manifestHeader(77, 1200);
        assertTrue(h.startsWith("#"), h);
        assertTrue(h.contains("77 frames in recipe order"), h);
        assertTrue(h.contains("settle 1200 ms"), h);
        assertTrue(h.contains("frame" + DevTools.SEP + "recipe"), "the header names the columns of the lines below it");
    }

    @Test void aLineIsFrameRecipeBrandValues() {
        assertEquals("01" + DevTools.SEP + "出厂默认（标准）" + DevTools.SEP + "索尼" + DevTools.SEP + Recipes.ALL[0].summary(),
                DevTools.manifestLine(1, 0));
        assertTrue(DevTools.manifestLine(10, 9).startsWith("10" + DevTools.SEP), "frames past nine are not padded further");
    }

    @Test void everyRecipeMakesOneParsableLineAndNoTwoNameTheSameRecipe() {
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < Recipes.ALL.length; i++) {
            String[] f = DevTools.manifestLine(i + 1, i).split("\\" + DevTools.SEP);
            assertEquals(4, f.length, "line " + i + " must split into exactly the four columns");
            assertEquals(i + 1, Integer.parseInt(f[0]), "the frame number is the position in the run");
            assertEquals(Recipes.ALL[i].name, f[1]);
            assertEquals(Recipes.GROUPS[Recipes.ALL[i].group], f[2]);
            assertTrue(names.add(f[1]), f[1] + " is listed twice");
        }
        assertEquals(Recipes.ALL.length, names.size());
    }
}
