package me.avinas.tempo.utils

import me.avinas.tempo.data.local.entities.Artist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Unicode-aware artist name normalization.
 *
 * Background: the old normalization used [^a-z0-9\s], which erased every
 * non-ASCII character. All artist names written purely in Japanese characters
 * (hiragana/katakana/kanji) normalized to the same empty string "", causing
 * every Japanese artist to collapse into a single artist row.
 */
class ArtistNormalizationTest {

    @Test
    fun `bigflo et oli remains a single artist with either common spelling`() {
        assertEquals(listOf("Bigflo et Oli"), ArtistParser.getAllArtists("Bigflo et Oli"))
        assertEquals(listOf("Bigflo & Oli"), ArtistParser.getAllArtists("Bigflo & Oli"))
    }

    // Artist.normalizeName

    @Test
    fun `japanese names are preserved and distinct`() {
        val yonezu = Artist.normalizeName("米津玄師")
        val utada = Artist.normalizeName("宇多田ヒカル")
        val aimyon = Artist.normalizeName("あいみょん")
        val yorushika = Artist.normalizeName("ヨルシカ")

        assertTrue(yonezu.isNotBlank())
        assertTrue(utada.isNotBlank())
        assertTrue(aimyon.isNotBlank())
        assertTrue(yorushika.isNotBlank())

        assertNotEquals(yonezu, utada)
        assertNotEquals(yonezu, aimyon)
        assertNotEquals(utada, aimyon)
        assertNotEquals(yonezu, yorushika)
    }

    @Test
    fun `same japanese name normalizes identically`() {
        assertEquals(Artist.normalizeName("米津玄師"), Artist.normalizeName("米津玄師"))
        assertEquals(Artist.normalizeName(" 米津玄師 "), Artist.normalizeName("米津玄師"))
    }

    @Test
    fun `nfkc folds full-width latin to ascii`() {
        // Full-width "ＹＯＡＳＯＢＩ" must equal half-width "YOASOBI"
        assertEquals("yoasobi", Artist.normalizeName("ＹＯＡＳＯＢＩ"))
        assertEquals(Artist.normalizeName("YOASOBI"), Artist.normalizeName("ＹＯＡＳＯＢＩ"))
    }

    @Test
    fun `nfkc folds half-width katakana to full-width`() {
        assertEquals(Artist.normalizeName("ヨルシカ"), Artist.normalizeName("ﾖﾙｼｶ"))
    }

    @Test
    fun `korean names are preserved`() {
        val iu = Artist.normalizeName("아이유")
        assertTrue(iu.isNotBlank())
        assertNotEquals(iu, Artist.normalizeName("방탄소년단"))
    }

    @Test
    fun `latin behavior unchanged`() {
        assertEquals("krsna", Artist.normalizeName("KRSNA"))
        assertEquals("krna", Artist.normalizeName("KR\$NA"))
        assertEquals("acdc", Artist.normalizeName("AC/DC"))
        assertEquals("billie eilish", Artist.normalizeName("  Billie   Eilish "))
    }

    @Test
    fun `accented latin is preserved instead of stripped`() {
        // Old behavior produced "bjrk"; new behavior keeps the letter
        assertEquals("björk", Artist.normalizeName("Björk"))
    }

    @Test
    fun `symbol-only names never produce blank key`() {
        // Names that strip to nothing must fall back to their original form
        // so two different symbol-only artists never share the "" dedup key.
        val stars = Artist.normalizeName("★★★")
        val checks = Artist.normalizeName("✓✓✓")
        val bangs = Artist.normalizeName("!!!")

        assertTrue(stars.isNotBlank())
        assertTrue(checks.isNotBlank())
        assertTrue(bangs.isNotBlank())
        assertNotEquals(stars, checks)
        assertNotEquals(stars, bangs)
    }

    // ArtistParser.normalizeForSearch

    @Test
    fun `normalizeForSearch preserves japanese and keeps existing mappings`() {
        assertEquals("米津玄師", ArtistParser.normalizeForSearch("米津玄師"))
        assertNotEquals(
            ArtistParser.normalizeForSearch("米津玄師"),
            ArtistParser.normalizeForSearch("宇多田ヒカル")
        )
        // Existing stylized-$ mapping must keep working
        assertEquals("kesha", ArtistParser.normalizeForSearch("Ke\$ha"))
        assertEquals("krsna", ArtistParser.normalizeForSearch("KR\$NA"))
    }

    @Test
    fun `normalizeForSearch folds latin diacritics but preserves other scripts`() {
        assertEquals("beyonce", ArtistParser.normalizeForSearch("Beyoncé"))
        // Devanagari matras survive — distinct words stay distinct
        assertNotEquals(
            ArtistParser.normalizeForSearch("कृष्णा"),
            ArtistParser.normalizeForSearch("कषण")
        )
        // Arabic is preserved
        assertEquals("عمرو دياب", ArtistParser.normalizeForSearch("عمرو دياب"))
    }

    // ArtistParser.isSameArtist

    @Test
    fun `isSameArtist rejects short cjk names contained in longer ones`() {
        // "周杰" is contained in "周杰伦" (Jay Chou) but is a DIFFERENT artist
        assertFalse(ArtistParser.isSameArtist("周杰", "周杰伦"))
        assertFalse(ArtistParser.isSameArtist("아이", "아이유"))
        // Longer names may still use containment ("The X" vs "X" style)
        assertTrue(ArtistParser.isSameArtist("米津玄師", "米津玄師 (Kenshi Yonezu)"))
    }

    @Test
    fun `isSameArtist matches identical japanese names`() {
        assertTrue(ArtistParser.isSameArtist("米津玄師", "米津玄師"))
        assertTrue(ArtistParser.isSameArtist("宇多田ヒカル", "宇多田ヒカル"))
    }

    @Test
    fun `isSameArtist rejects different japanese names`() {
        assertFalse(ArtistParser.isSameArtist("米津玄師", "宇多田ヒカル"))
        assertFalse(ArtistParser.isSameArtist("あいみょん", "ヨルシカ"))
    }

    @Test
    fun `isSameArtist never matches two blank-normalizing names`() {
        // Both strip to "" — the guard must prevent a false positive
        assertFalse(ArtistParser.isSameArtist("!!!", "???"))
        assertFalse(ArtistParser.isSameArtist("!!!", "米津玄師"))
    }

    @Test
    fun `isSameArtist keeps existing latin matching behavior`() {
        assertTrue(ArtistParser.isSameArtist("KRSNA", "krsna"))
        assertTrue(ArtistParser.isSameArtist("The Chainsmokers", "Chainsmokers"))
        assertFalse(ArtistParser.isSameArtist("Kendrick Lamar", "Kanye West"))
    }

    // ArtistParser.isStrictSameArtist

    @Test
    fun `isStrictSameArtist guards blanks and matches japanese`() {
        assertTrue(ArtistParser.isStrictSameArtist("米津玄師", "米津玄師"))
        assertFalse(ArtistParser.isStrictSameArtist("米津玄師", "宇多田ヒカル"))
        assertFalse(ArtistParser.isStrictSameArtist("!!!", "???"))
    }
}
