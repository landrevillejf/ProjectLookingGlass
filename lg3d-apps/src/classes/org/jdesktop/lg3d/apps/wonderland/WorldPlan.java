/*
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The top-down footprint of one published world: every placed room drawn as a
 * rectangle at its real relative position, normalized so any panel can scale it
 * to whatever space it has. This is the computation behind the browser's
 * vignette when a world has no picture &mdash; the answer to "what does this
 * world look like?" that a 2D desktop can honestly give without a 3D canvas: the
 * server already describes where each room sits ({@code x}/{@code z}) and how big
 * it is ({@code width}/{@code depth}), so the plan is not a picture, it is the
 * world's own data read from above.
 *
 * <p>Pure and headless by design, exactly like {@link WorldCatalog}: no AWT
 * types, no painting. The rules that make a plan readable are decisions, and
 * decisions want tests:</p>
 * <ul>
 *   <li>The environment root is not a room; it never appears.</li>
 *   <li>Neither is the <em>ground</em>: the one cell whose footprint is most of
 *       the world (a floor slab, a grass circle) is excluded from the rooms and
 *       from the scale, because letting it set the bounds shrinks every real
 *       room to a speck &mdash; the exact failure that made the first version of
 *       this vignette unreadable. It is reported separately as
 *       {@link #ground()} so a panel can still draw it as the frame.</li>
 *   <li>An unplaced cell (no {@code x}/{@code z} in the document) is skipped —
 *       inventing a position for it would lie about the world.</li>
 *   <li>A missing footprint draws as a unit square: small and uniform, not a
 *       misleading zero or a guessed size.</li>
 *   <li>Aspect ratio is preserved (one uniform scale for both axes) and the
 *       content is centered, so a long thin world stays long and thin.</li>
 *   <li>The world's +z axis points up on screen, the conventional map
 *       orientation.</li>
 *   <li>A world whose cells all share one point would collapse to zero span;
 *       the bounds are inflated to a floor epsilon instead, so the markers
 *       still land inside the content box rather than dividing by zero.</li>
 *   <li>{@link #contentBounds()} says how much of the normalized box the rooms
 *       actually fill. A panel that fits the whole unit square into its own
 *       rectangle throws away the difference between a wide world and a square
 *       viewport; fitting the content instead is what makes the plan big enough
 *       to read.</li>
 * </ul>
 */
final class WorldPlan {

    /** Margin kept clear around the content, in normalized units. */
    static final double PAD = 0.06;

    /** The footprint a cell with no recorded size draws with. */
    static final double DEFAULT_SIZE = 1.0;

    /** The smallest span a world may occupy before its bounds are inflated. */
    static final double MIN_SPAN = 1e-3;

    /**
     * How much of the world's own extent a cell must cover, on both axes, to be
     * read as the ground rather than a room. Deliberately blunt: 3/4 of the
     * world in both directions is not a room in a world, it is the world's
     * floor, and only the single largest cell can ever qualify.
     */
    static final double GROUND_FRACTION = 0.75;

    /**
     * One room of the plan: a rectangle in normalized coordinates
     * ({@code y} measured down the panel, as Swing paints it) and the label to
     * write on it.
     */
    record Item(String label, double x, double y, double w, double h) {
    }

    /** A plan with nothing to show: the empty world, or one with no placement. */
    static final WorldPlan EMPTY = new WorldPlan(List.of(), null, 0, 0);

    private final List<Item> items;
    private final Item ground;
    private final double contentW;
    private final double contentH;

    private WorldPlan(List<Item> items, Item ground, double contentW, double contentH) {
        this.items = List.copyOf(items);
        this.ground = ground;
        this.contentW = contentW;
        this.contentH = contentH;
    }

    /**
     * Fold a published world's cells into the plan. {@code cells} in any order;
     * the items keep the cells' order so two reads of one document paint the
     * same way.
     */
    static WorldPlan of(List<WorldCatalog.Cell> cells) {
        List<WorldCatalog.Cell> placed = new ArrayList<>();
        for (WorldCatalog.Cell cell : cells) {
            if (cell.placed() && !isEnvironment(cell)) {
                placed.add(cell);
            }
        }
        int groundAt = findGround(placed);
        List<WorldCatalog.Cell> rooms = new ArrayList<>(placed);
        Item ground = null;
        WorldCatalog.Cell groundCell = null;
        if (groundAt >= 0) {
            groundCell = rooms.remove(groundAt);
        }
        if (rooms.isEmpty()) {
            return EMPTY;
        }

        double[] geo = new double[rooms.size() * 4]; // cx, cz, w, d per room
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < rooms.size(); i++) {
            WorldCatalog.Cell cell = rooms.get(i);
            double w = cell.width == null ? DEFAULT_SIZE : cell.width;
            double d = cell.depth == null ? DEFAULT_SIZE : cell.depth;
            geo[i * 4] = cell.x;
            geo[i * 4 + 1] = cell.z;
            geo[i * 4 + 2] = w;
            geo[i * 4 + 3] = d;
            minX = Math.min(minX, cell.x - w / 2);
            maxX = Math.max(maxX, cell.x + w / 2);
            minZ = Math.min(minZ, cell.z - d / 2);
            maxZ = Math.max(maxZ, cell.z + d / 2);
        }
        double spanX = maxX - minX;
        double spanZ = maxZ - minZ;
        if (spanX < MIN_SPAN) {
            double grow = (MIN_SPAN - spanX) / 2;
            minX -= grow;
            maxX += grow;
            spanX = MIN_SPAN;
        }
        if (spanZ < MIN_SPAN) {
            double grow = (MIN_SPAN - spanZ) / 2;
            minZ -= grow;
            maxZ += grow;
            spanZ = MIN_SPAN;
        }

        // One uniform scale fits the longer axis into the padded box; the
        // shorter one is centered inside what remains, so aspect survives.
        double scale = (1 - 2 * PAD) / Math.max(spanX, spanZ);
        double originX = (1 - spanX * scale) / 2;
        double originY = (1 - spanZ * scale) / 2;

        List<Item> items = new ArrayList<>(rooms.size());
        for (int i = 0; i < rooms.size(); i++) {
            double cx = geo[i * 4], cz = geo[i * 4 + 1];
            double w = geo[i * 4 + 2], d = geo[i * 4 + 3];
            double x = originX + (cx - w / 2 - minX) * scale;
            double y = originY + (maxZ - (cz + d / 2)) * scale; // +z up
            items.add(new Item(rooms.get(i).label(), x, y, w * scale, d * scale));
        }
        if (groundCell != null) {
            // The ground keeps the same transform, so it lands where the world
            // put it -- usually hanging outside the content box, which is the
            // truth: the floor is bigger than the rooms on it.
            double w = groundCell.width == null ? DEFAULT_SIZE : groundCell.width;
            double d = groundCell.depth == null ? DEFAULT_SIZE : groundCell.depth;
            ground = new Item(groundCell.label(),
                    originX + (groundCell.x - w / 2 - minX) * scale,
                    originY + (maxZ - (groundCell.z + d / 2)) * scale,
                    w * scale, d * scale);
        }
        double contentW = items.stream().mapToDouble(i -> i.x() + i.w()).max()
                .orElse(0) - items.stream().mapToDouble(Item::x).min().orElse(0);
        double contentH = items.stream().mapToDouble(i -> i.y() + i.h()).max()
                .orElse(0) - items.stream().mapToDouble(Item::y).min().orElse(0);
        return new WorldPlan(items, ground, contentW, contentH);
    }

    /**
     * The index of the ground cell in {@code placed}, or -1 when every placed
     * cell is a room. Only the largest footprint can qualify, and only when
     * there is something else in the world to be the ground's rooms.
     */
    private static int findGround(List<WorldCatalog.Cell> placed) {
        if (placed.size() < 2) {
            return -1;
        }
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (WorldCatalog.Cell cell : placed) {
            double w = cell.width == null ? DEFAULT_SIZE : cell.width;
            double d = cell.depth == null ? DEFAULT_SIZE : cell.depth;
            minX = Math.min(minX, cell.x - w / 2);
            maxX = Math.max(maxX, cell.x + w / 2);
            minZ = Math.min(minZ, cell.z - d / 2);
            maxZ = Math.max(maxZ, cell.z + d / 2);
        }
        double spanX = maxX - minX;
        double spanZ = maxZ - minZ;
        int best = -1;
        double bestArea = 0;
        for (int i = 0; i < placed.size(); i++) {
            WorldCatalog.Cell cell = placed.get(i);
            double w = cell.width == null ? DEFAULT_SIZE : cell.width;
            double d = cell.depth == null ? DEFAULT_SIZE : cell.depth;
            if (w >= GROUND_FRACTION * spanX && d >= GROUND_FRACTION * spanZ
                    && w * d > bestArea) {
                best = i;
                bestArea = w * d;
            }
        }
        return best;
    }

    /** The environment root is the world's floor, not a room on it. */
    private static boolean isEnvironment(WorldCatalog.Cell cell) {
        return cell.simpleClassName().equals("EnvironmentCell");
    }

    /** The rooms, in the cells' publication order. */
    List<Item> items() {
        return items;
    }

    /** The world's floor slab, in the same normalized space, if it had one. */
    Optional<Item> ground() {
        return Optional.ofNullable(ground);
    }

    /**
     * The box the rooms actually occupy, in normalized units:
     * {@code {minX, minY, width, height}}. A panel scales and centers
     * <em>this</em> into its own rectangle, not the unit square, so a wide world
     * uses a wide viewport instead of painting a postage stamp in the middle.
     */
    double[] contentBounds() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        for (Item item : items) {
            minX = Math.min(minX, item.x());
            minY = Math.min(minY, item.y());
        }
        return new double[] {minX, minY, contentW, contentH};
    }

    /** Whether there is nothing to paint (and the placeholder says so). */
    boolean isEmpty() {
        return items.isEmpty();
    }
}
