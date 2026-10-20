/*
 * M2d: the statistics layer, ported verbatim. Pins the swappable provider (the
 * no-op default and a real SPI), the CellStat identity contract, and every value
 * rendering -- String/Long accumulation, TimeCellStat unit suffixes and the
 * SizeCellStat b/kb/mb thresholds -- which the 2006 HUD read straight off.
 */
package org.jdesktop.wonderland.client.cell;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CellStatisticsTest {

    private final Cell anyCell = new CellTestFixtures.CacheableCell(
            CellTestFixtures.id(1), null);

    @Test
    void defaultProviderIsNoop() {
        CellStatistics stats = new CellStatistics();
        assertThat(stats.getProvider()).isInstanceOf(CellStatistics.NoopCellStatisticsProvider.class);
        stats.add(anyCell, new CellStatistics.LongCellStat("x"));
        assertThat(stats.get(anyCell, "x")).isNull();
        assertThat(stats.getAll(anyCell)).isEmpty();
        assertThat(stats.remove(anyCell, "x")).isNull();
    }

    @Test
    void swappableProviderStoresAndReadsBack() {
        CellStatistics stats = new CellStatistics();
        CellStatistics.CellStatisticsSPI provider = new CellTestFixtures.RecordingStatsProvider();
        stats.setProvider(provider);
        assertThat(stats.getProvider()).isSameAs(provider);

        CellStatistics.LongCellStat stat = new CellStatistics.LongCellStat("count");
        stats.add(anyCell, stat);
        assertThat(stats.get(anyCell, "count")).isSameAs(stat);
        assertThat(stats.getAll(anyCell)).containsExactly(stat);
        assertThat(stats.remove(anyCell, "count")).isSameAs(stat);
        assertThat(stats.get(anyCell, "count")).isNull();
    }

    @Test
    void stringStatRoundTrips() {
        CellStatistics.StringCellStat s = new CellStatistics.StringCellStat("id", "value");
        assertThat(s.getId()).isEqualTo("id");
        assertThat(s.getDescription()).isEqualTo("id");
        assertThat(s.getValue()).isEqualTo("value");
        s.setValue("other");
        s.setDescription("desc");
        assertThat(s.getValue()).isEqualTo("other");
        assertThat(s.getDescription()).isEqualTo("desc");
    }

    @Test
    void longStatAccumulates() {
        CellStatistics.LongCellStat l = new CellStatistics.LongCellStat("l", "Long", 5L);
        assertThat(l.getLongValue()).isEqualTo(5L);
        assertThat(l.getValue()).isEqualTo("5");
        assertThat(l.changeValue(3L)).isEqualTo(8L);
        l.setValue(100L);
        assertThat(l.getLongValue()).isEqualTo(100L);

        assertThat(new CellStatistics.LongCellStat("a").getValue()).isEqualTo("0");
        assertThat(new CellStatistics.LongCellStat("a", "b").getValue()).isEqualTo("0");
    }

    @Test
    void timeStatSuffixesEveryUnit() {
        assertThat(new CellStatistics.TimeCellStat("t").getSuffix()).isEqualTo("ms.");
        assertThat(new CellStatistics.TimeCellStat("t", "d").getTimeUnit())
                .isSameAs(TimeUnit.MILLISECONDS);
        assertThat(suffix(TimeUnit.DAYS)).isEqualTo("days");
        assertThat(suffix(TimeUnit.HOURS)).isEqualTo("hours");
        assertThat(suffix(TimeUnit.MINUTES)).isEqualTo("min.");
        assertThat(suffix(TimeUnit.SECONDS)).isEqualTo("s.");
        assertThat(suffix(TimeUnit.MILLISECONDS)).isEqualTo("ms.");
        assertThat(suffix(TimeUnit.MICROSECONDS)).isEqualTo("us.");
        assertThat(suffix(TimeUnit.NANOSECONDS)).isEqualTo("ns.");
        CellStatistics.TimeCellStat t = suffixStat(TimeUnit.SECONDS);
        t.setValue(2L);
        assertThat(t.getValue()).isEqualTo("2 s.");
    }

    private String suffix(TimeUnit unit) {
        return suffixStat(unit).getSuffix();
    }

    private CellStatistics.TimeCellStat suffixStat(TimeUnit unit) {
        return new CellStatistics.TimeCellStat("t", "d", unit);
    }

    @Test
    void sizeStatFormatsAcrossThresholds() {
        assertThat(sizeValue(500L)).isEqualTo("500 b.");
        assertThat(sizeValue(1500L)).isEqualTo("1.500 kb.");
        assertThat(sizeValue(2_500_000L)).isEqualTo("2.500 mb.");
    }

    @Test
    void sizeStatTwoArgConstructorCarriesIdAndDescription() {
        // The public 2-arg ctor is the one a HUD writes when the label reads
        // better than the id ("Cell size" over "sz"); pin it against the same
        // id/description contract the 1-arg form documents.
        CellStatistics.SizeCellStat s = new CellStatistics.SizeCellStat("sz", "Size");
        assertThat(s.getId()).isEqualTo("sz");
        assertThat(s.getDescription()).isEqualTo("Size");
        assertThat(s.getValue()).isEqualTo("0 b.");
    }

    private String sizeValue(long bytes) {
        CellStatistics.SizeCellStat s = new CellStatistics.SizeCellStat("sz");
        s.setValue(bytes);
        return s.getValue();
    }

    @Test
    void cellStatIdentityIsById() {
        CellStatistics.LongCellStat a = new CellStatistics.LongCellStat("same");
        CellStatistics.LongCellStat b = new CellStatistics.LongCellStat("same");
        CellStatistics.LongCellStat c = new CellStatistics.LongCellStat("other");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(null);
        // a different CellStat subclass with the same id is NOT equal
        assertThat(a).isNotEqualTo(new CellStatistics.StringCellStat("same", "x"));
        // the null-id branch of hashCode contributes 0, not a NPE
        assertThat(new IdStat(null).hashCode()).isEqualTo(73 * 7);
    }

    @Test
    void nullIdEqualsOnlyWhenBothNull() {
        // a named subclass so both operands share getClass() and reach the id logic
        assertThat(new IdStat(null).equals(new IdStat("x"))).isFalse();   // null vs set
        assertThat(new IdStat(null).equals(new IdStat(null))).isTrue();    // both null
        assertThat(new IdStat("a").equals(new IdStat("b"))).isFalse();    // differ
        assertThat(new IdStat("a").equals(new IdStat("a"))).isTrue();     // same
    }

    /** A CellStat whose id is whatever the ctor is given, so every equals branch is settable. */
    static class IdStat extends CellStatistics.CellStat {
        IdStat(String id) {
            super(id, id);
        }

        @Override
        public String getValue() {
            return "";
        }
    }
}
