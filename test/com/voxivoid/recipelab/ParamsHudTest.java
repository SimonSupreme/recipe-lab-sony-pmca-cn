package com.voxivoid.recipelab;

import static com.voxivoid.recipelab.Fixtures.*;
import static com.voxivoid.recipelab.Params.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The overlay text: the meta line under the recipe name, the minimal pill, the quality prompt. */
class ParamsHudTest {

    @Test void metaLineForACreativeStyleRecipe() {
        int[] cur = factoryRows();
        assertEquals("标准  ·  白平衡 自动", metaLine(cur, cur.clone(), null, Recipes.FLASH_NONE));
        int[] e = staged(recipe("柯达 Portra 400"), cur, Q_FINE);
        assertEquals("肖像  ·  白平衡 自动  ·  EV +0.7", metaLine(cur, e, null, Recipes.FLASH_NONE));
        e = staged(recipe("Velvia 鲜艳"), cur, Q_FINE);
        assertEquals("生动  ·  白平衡 自动  ·  PP3 矩阵", metaLine(cur, e, null, Recipes.FLASH_NONE));
        e = staged(recipe("柯达 Vision3 500T（日光）"), cur, Q_FINE);
        assertEquals("中性  ·  白平衡 3200K  ·  EV +0.3  ·  DRO Lv3", metaLine(cur, e, null, Recipes.FLASH_NONE));
    }

    @Test void metaLineForAnEffectRecipe() {
        int[] cur = factoryRows();
        int[] e = staged(recipe("索尼 SH（柔和高亮）"), cur, Q_FINE);
        assertEquals("照片效果 高亮 蓝（创意风格被忽略，仅限 JPEG）  ·  白平衡 自动  ·  EV +1.0", metaLine(cur, e, null, Recipes.FLASH_NONE));
        e = staged(recipe("Acros + 红滤镜"), cur, Q_FINE);
        assertEquals("照片效果 强反差单色（创意风格被忽略，仅限 JPEG）  ·  白平衡 2500K", metaLine(cur, e, null, Recipes.FLASH_NONE));
    }

    @Test void metaLineAnnouncesAQualityChangeAndRawUnderAnEffect() {
        int[] cur = factoryRows(); cur[R_QUAL] = Q_RAW;
        int[] e = staged(recipe("GR 复古"), cur, Q_RAW);
        assertEquals("照片效果 复古（创意风格被忽略，仅限 JPEG）  ·  白平衡 自动  ·  画质 → JPG 精细（当前 RAW）", metaLine(cur, e, null, Recipes.FLASH_NONE));
        e[R_QUAL] = Q_RAW;   // the user forced RAW back on
        assertEquals("照片效果 复古（创意风格被忽略，仅限 JPEG）  ·  白平衡 自动  ·  RAW 开启中：特效将被忽略", metaLine(cur, e, null, Recipes.FLASH_NONE));
    }

    @Test void metaLineShowsAnUnknownWhiteBalanceModeAndThePreviewError() {
        int[] cur = factoryRows(); int[] e = cur.clone(); e[R_WBMODE] = 3;
        assertEquals("标准  ·  白平衡 模式 3  ·  无实时预览：CameraEx not found", metaLine(cur, e, "CameraEx not found", Recipes.FLASH_NONE));
    }

    /** the flash advice rides the existing line: zero new rows, and always labelled as advice only */
    @Test void metaLineCarriesTheFlashAdviceOfACustomRecipe() {
        int[] cur = factoryRows();
        int[] e = staged(recipe("柯达 Portra 400"), cur, Q_FINE);
        String line = metaLine(cur, e, null, Recipes.FLASH_ON);
        assertTrue(line.contains("建议直闪（仅建议）"), line);
        assertEquals("肖像  ·  白平衡 自动  ·  EV +0.7  ·  建议直闪（仅建议）", line);
        line = metaLine(cur, e, null, Recipes.FLASH_SOFT);
        assertTrue(line.endsWith("建议柔光（仅建议）"), line);
        line = metaLine(cur, e, null, -1);   // an out-of-table value shows nothing rather than crashing
        assertFalse(line.contains("建议"), line);
    }

    @Test void miniPill() {
        int[] cur = factoryRows();
        int i = indexOf("柯达 Portra 400");
        int[] e = staged(Recipes.ALL[i], cur, Q_FINE);
        assertEquals("风格  柯达 Portra 400   " + (i + 1) + " / 77   · 预览中", miniLine(i, cur, e, true));
        assertEquals("风格  柯达 Portra 400   " + (i + 1) + " / 77   · 已生效", miniLine(i, cur, e, false));
        i = indexOf("GR 复古"); cur[R_QUAL] = Q_RAW;
        e = staged(Recipes.ALL[i], cur, Q_RAW);
        assertEquals("特效  GR 复古   " + (i + 1) + " / 77   · 预览中   · 画质 → JPG 精细", miniLine(i, cur, e, true));
    }

    @Test void qualityPromptExplainsWhyTheQualityMoves() {
        int[] cur = factoryRows(); cur[R_QUAL] = Q_RAW;
        int[] e = staged(recipe("GR 复古"), cur, Q_RAW);
        assertArrayEquals(new String[] { "画质：RAW  →  JPG 精细", "应用此配方需要 JPEG 格式。" }, qualityPrompt(cur, e));
        e = cur.clone(); e[R_QUAL] = Q_STD;
        assertArrayEquals(new String[] { "画质：RAW  →  JPG 标准", "创意风格配方使用出厂配方的画质。" }, qualityPrompt(cur, e));
    }
}
