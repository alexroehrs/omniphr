package br.unisinos.omniphr.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paginated view over the patient's datablocks.
 *
 * The model includes a premise of paging records: the user always accesses
 * the latest data first, in a paginated way, from the most recent block to
 * the oldest. This speeds up access and reduces traffic, since the whole
 * health record is never fetched at once.
 */
public final class Page {

    private final List<Datablock> items;
    private final int pageNumber;   // 1-based
    private final int pageSize;
    private final long totalBlocks;

    private Page(List<Datablock> items, int pageNumber, int pageSize, long totalBlocks) {
        this.items = items;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
        this.totalBlocks = totalBlocks;
    }

    /** Orders blocks from the most recent to the oldest and cuts the requested page. */
    public static Page of(List<Datablock> allBlocks, int pageNumber, int pageSize) {
        List<Datablock> sorted = new ArrayList<>(allBlocks);
        sorted.sort(Comparator.comparing(Datablock::getCreatedAt).reversed()
                .thenComparing(Comparator.comparing(Datablock::getSequence).reversed()));
        int from = Math.min((pageNumber - 1) * pageSize, sorted.size());
        int to = Math.min(from + pageSize, sorted.size());
        return new Page(new ArrayList<>(sorted.subList(from, to)), pageNumber, pageSize, sorted.size());
    }

    public List<Datablock> getItems() {
        return items;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotalBlocks() {
        return totalBlocks;
    }

    public boolean hasNext() {
        return (long) pageNumber * pageSize < totalBlocks;
    }

    @Override
    public String toString() {
        return "Page " + pageNumber + " (" + items.size() + " of " + totalBlocks + " blocks, hasNext=" + hasNext() + ")";
    }
}
