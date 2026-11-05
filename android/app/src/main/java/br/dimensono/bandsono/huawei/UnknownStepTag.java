package br.dimensono.bandsono.huawei;

/** A minute-record field this code does not decode yet, as counted in one sync (timestamps in unix seconds). */
public final class UnknownStepTag {
    public final int bitmap;
    /** The field's tag, 0..255. */
    public final int tag;
    public int count;
    /** The value of the first occurrence (the catalog keeps one sample). */
    public final int sampleValue;
    public int firstTs, lastTs;

    public UnknownStepTag(int bitmap, int tag, int count, int sampleValue, int firstTs, int lastTs) {
        this.bitmap = bitmap;
        this.tag = tag & 0xff;
        this.count = count;
        this.sampleValue = sampleValue;
        this.firstTs = firstTs;
        this.lastTs = lastTs;
    }

    /** One more occurrence at {@code timestamp}. */
    public void add(int timestamp) {
        count++;
        firstTs = Math.min(firstTs, timestamp);
        lastTs = Math.max(lastTs, timestamp);
    }
}
