package io.vulscan.sqli.report

import io.vulscan.kit.contracts.Verdict
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Q-6: базовые очки по вердикту + корректировки (СУБД известна / нестабильный baseline / частичное совпадение контроля). */
class SqliConfidenceScorerTest {
    @Test
    fun `base scores by verdict with no adjustments`() {
        assertThat(SqliConfidenceScorer.score(Verdict.SUSPECTED, dbmsKnown = false, anyUnstableBaseline = false, anyPartialControlMatch = false))
            .isEqualTo(30)
        assertThat(SqliConfidenceScorer.score(Verdict.LIKELY, dbmsKnown = false, anyUnstableBaseline = false, anyPartialControlMatch = false))
            .isEqualTo(70)
        assertThat(SqliConfidenceScorer.score(Verdict.CONFIRMED, dbmsKnown = false, anyUnstableBaseline = false, anyPartialControlMatch = false))
            .isEqualTo(92)
    }

    @Test
    fun `known dbms adds five points`() {
        assertThat(SqliConfidenceScorer.score(Verdict.LIKELY, dbmsKnown = true, anyUnstableBaseline = false, anyPartialControlMatch = false))
            .isEqualTo(75)
    }

    @Test
    fun `unstable baseline and partial control match each subtract ten`() {
        assertThat(SqliConfidenceScorer.score(Verdict.CONFIRMED, dbmsKnown = false, anyUnstableBaseline = true, anyPartialControlMatch = false))
            .isEqualTo(82)
        assertThat(SqliConfidenceScorer.score(Verdict.CONFIRMED, dbmsKnown = false, anyUnstableBaseline = false, anyPartialControlMatch = true))
            .isEqualTo(82)
        assertThat(SqliConfidenceScorer.score(Verdict.CONFIRMED, dbmsKnown = false, anyUnstableBaseline = true, anyPartialControlMatch = true))
            .isEqualTo(72)
    }

    @Test
    fun `result is clamped into the 1 to 99 range`() {
        assertThat(SqliConfidenceScorer.score(Verdict.SUSPECTED, dbmsKnown = false, anyUnstableBaseline = true, anyPartialControlMatch = true))
            .isEqualTo(10)
        assertThat(SqliConfidenceScorer.score(Verdict.CONFIRMED, dbmsKnown = true, anyUnstableBaseline = false, anyPartialControlMatch = false))
            .isEqualTo(97)
    }
}
