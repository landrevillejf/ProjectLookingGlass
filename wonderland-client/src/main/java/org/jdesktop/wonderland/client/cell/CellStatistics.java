/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * A per-cell statistics bag: the cache attaches named CellStat values (timings,
 * sizes, strings) to each cell through a swappable CellStatisticsSPI provider;
 * the default provider is a no-op so the statistics layer costs nothing until a
 * HUD/monitor installs a real one. Pure JDK -- fully headless and fully tested.
 * The only migration edit is Collections.EMPTY_SET -> Collections.emptySet().
 */
package org.jdesktop.wonderland.client.cell;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class CellStatistics {

    private CellStatisticsSPI provider = new NoopCellStatisticsProvider();

    public synchronized void setProvider(CellStatisticsSPI provider) {
        this.provider = provider;
    }

    public synchronized CellStatisticsSPI getProvider() {
        return this.provider;
    }

    public synchronized void add(Cell cell, CellStat stat) {
        this.getProvider().add(cell, stat);
    }

    public synchronized CellStat get(Cell cell, String id) {
        return this.getProvider().get(cell, id);
    }

    public synchronized Collection<CellStat> getAll(Cell cell) {
        return this.getProvider().getAll(cell);
    }

    public synchronized CellStat remove(Cell cell, String id) {
        return this.getProvider().remove(cell, id);
    }

    class NoopCellStatisticsProvider implements CellStatisticsSPI {
        @Override
        public void add(Cell cell, CellStat stat) {
        }

        @Override
        public CellStat get(Cell cell, String id) {
            return null;
        }

        @Override
        public Collection<CellStat> getAll(Cell cell) {
            return Collections.emptySet();
        }

        @Override
        public CellStat remove(Cell cell, String id) {
            return null;
        }
    }

    public abstract static class CellStat {
        private final String id;
        private String desc;

        protected CellStat(String id, String desc) {
            this.id = id;
            this.desc = desc;
        }

        public String getId() {
            return this.id;
        }

        public String getDescription() {
            return this.desc;
        }

        public void setDescription(String description) {
            this.desc = description;
        }

        public abstract String getValue();

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            } else if (this.getClass() != obj.getClass()) {
                return false;
            } else {
                CellStat other = (CellStat) obj;
                if (this.id == null) {
                    if (other.id != null) {
                        return false;
                    }
                } else if (!this.id.equals(other.id)) {
                    return false;
                }

                return true;
            }
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 73 * hash + (this.id != null ? this.id.hashCode() : 0);
            return hash;
        }
    }

    public static class StringCellStat extends CellStat {
        private String value;

        public StringCellStat(String id, String value) {
            this(id, id, value);
        }

        public StringCellStat(String id, String desc, String value) {
            super(id, desc);
            this.value = value;
        }

        @Override
        public synchronized String getValue() {
            return this.value;
        }

        public synchronized void setValue(String value) {
            this.value = value;
        }
    }

    public static class LongCellStat extends CellStat {
        private long value;

        public LongCellStat(String id) {
            this(id, id);
        }

        public LongCellStat(String id, String desc) {
            this(id, desc, 0L);
        }

        public LongCellStat(String id, String desc, long initialValue) {
            super(id, desc);
            this.value = initialValue;
        }

        @Override
        public synchronized String getValue() {
            return String.valueOf(this.value);
        }

        public synchronized long getLongValue() {
            return this.value;
        }

        public synchronized void setValue(Long value) {
            this.value = value;
        }

        public synchronized Long changeValue(Long amount) {
            this.value += amount;
            return this.value;
        }
    }

    public static class TimeCellStat extends LongCellStat {
        TimeUnit timeUnit;

        public TimeCellStat(String id) {
            super(id);
            this.timeUnit = TimeUnit.MILLISECONDS;
        }

        public TimeCellStat(String id, String desc) {
            super(id, desc, 0L);
            this.timeUnit = TimeUnit.MILLISECONDS;
        }

        public TimeCellStat(String id, String desc, TimeUnit timeUnit) {
            super(id, desc, 0L);
            this.timeUnit = timeUnit;
        }

        @Override
        public String getValue() {
            return this.getLongValue() + " " + this.getSuffix();
        }

        public synchronized TimeUnit getTimeUnit() {
            return this.timeUnit;
        }

        protected String getSuffix() {
            switch (this.getTimeUnit()) {
                case DAYS:
                    return "days";
                case HOURS:
                    return "hours";
                case MICROSECONDS:
                    return "us.";
                case MILLISECONDS:
                    return "ms.";
                case MINUTES:
                    return "min.";
                case NANOSECONDS:
                    return "ns.";
                case SECONDS:
                    return "s.";
                default:
                    return "";
            }
        }
    }

    public static class SizeCellStat extends LongCellStat {
        public SizeCellStat(String id) {
            super(id);
        }

        public SizeCellStat(String id, String desc) {
            super(id, desc, 0L);
        }

        @Override
        public String getValue() {
            if (this.getLongValue() < 1000L) {
                return this.getLongValue() + " b.";
            } else {
                return this.getLongValue() < 1000000L
                        ? String.format("%.3f kb.", (float) this.getLongValue() / 1000.0F)
                        : String.format("%.3f mb.", (float) this.getLongValue() / 1000000.0F);
            }
        }
    }

    public interface CellStatisticsSPI {
        void add(Cell cell, CellStat stat);

        CellStat get(Cell cell, String id);

        Collection<CellStat> getAll(Cell cell);

        CellStat remove(Cell cell, String id);
    }
}
