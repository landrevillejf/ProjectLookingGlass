/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.solitaire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * A complete Klondike solitaire engine: a 52-card deck dealt into seven tableau
 * piles, a stock that draws one card at a time and recycles, four foundations
 * built up by suit from Ace to King, and tableau stacks built down in
 * alternating colours. Kings (or a King-headed run) fill an empty column, the
 * top face-down card of a column flips up when exposed, and the game is won
 * once every foundation is complete.
 *
 * <p>The engine is pure Java (no lg3d / AWT dependency) so it can be exercised
 * headlessly. A card is an {@code int} {@code suit * 13 + rank} with suit
 * {@code 0=club, 1=diamond, 2=heart, 3=spade} and rank {@code 0=Ace .. 12=King};
 * diamonds and hearts are red. Every action snapshots the state first, so
 * {@link #undo()} rolls back exactly one move.</p>
 */
public class SolitaireModel {

    public static final int CLUBS = 0, DIAMONDS = 1, HEARTS = 2, SPADES = 3;

    /** Interaction zones, used by the view's click picking and the hint. */
    public static final int ZONE_STOCK = 0, ZONE_WASTE = 1,
            ZONE_FOUNDATION = 2, ZONE_TABLEAU = 3, ZONE_NONE = -1;

    private static final int SUITS = 4;
    private static final int RANKS = 13;
    private static final int TABLEAU = 7;

    private final Random rnd = new Random();

    private final ArrayList<Integer> stock = new ArrayList<Integer>();
    private final ArrayList<Integer> waste = new ArrayList<Integer>();
    @SuppressWarnings("unchecked")
    private final ArrayList<Integer>[] foundation = new ArrayList[SUITS];
    @SuppressWarnings("unchecked")
    private final ArrayList<Integer>[] tableau = new ArrayList[TABLEAU];
    private final int[] tabUp = new int[TABLEAU];

    private final Deque<Snapshot> undoStack = new ArrayDeque<Snapshot>();
    private int moves;

    public SolitaireModel() {
        for (int i = 0; i < SUITS; i++) {
            foundation[i] = new ArrayList<Integer>();
        }
        for (int i = 0; i < TABLEAU; i++) {
            tableau[i] = new ArrayList<Integer>();
        }
        newGame();
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    public final void newGame() {
        List<Integer> deck = new ArrayList<Integer>(52);
        for (int c = 0; c < 52; c++) {
            deck.add(c);
        }
        Collections.shuffle(deck, rnd);
        int d = 0;
        for (int i = 0; i < TABLEAU; i++) {
            tableau[i].clear();
            for (int k = 0; k <= i; k++) {
                tableau[i].add(deck.get(d++));
            }
            tabUp[i] = i;              // only the last dealt card is face-up
        }
        stock.clear();
        while (d < 52) {
            stock.add(deck.get(d++));
        }
        waste.clear();
        for (int i = 0; i < SUITS; i++) {
            foundation[i].clear();
        }
        undoStack.clear();
        moves = 0;
    }

    /** Re-seeds the shuffle for reproducible deals (tests). */
    public void setSeed(long seed) {
        rnd.setSeed(seed);
    }

    // ------------------------------------------------------------------
    // Card helpers
    // ------------------------------------------------------------------

    public static int suit(int card) {
        return card / RANKS;
    }

    public static int rank(int card) {
        return card % RANKS;
    }

    public static boolean isRed(int card) {
        int s = suit(card);
        return s == DIAMONDS || s == HEARTS;
    }

    public static char rankChar(int card) {
        int r = rank(card);
        if (r == 0) return 'A';
        if (r < 9) return (char) ('1' + r);   // 2..9  (r=1 -> '2')
        if (r == 9) return 'T';               // placeholder, host maps to "10"
        if (r == 10) return 'J';
        if (r == 11) return 'Q';
        return 'K';
    }

    public static String rankLabel(int card) {
        return rank(card) == 9 ? "10" : Character.toString(rankChar(card));
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public int stockSize() {
        return stock.size();
    }

    public int wasteSize() {
        return waste.size();
    }

    public int wasteTop() {
        return waste.isEmpty() ? -1 : waste.get(waste.size() - 1);
    }

    public int foundationSize(int f) {
        return foundation[f].size();
    }

    public int foundationTop(int f) {
        return foundation[f].isEmpty() ? -1 : foundation[f].get(foundation[f].size() - 1);
    }

    public int tableauSize(int p) {
        return tableau[p].size();
    }

    public int tableauCard(int p, int pos) {
        return tableau[p].get(pos);
    }

    public int tabUpIndex(int p) {
        return tabUp[p];
    }

    public boolean isFaceUp(int p, int pos) {
        return pos >= tabUp[p];
    }

    public int getMoves() {
        return moves;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean isWon() {
        for (int i = 0; i < SUITS; i++) {
            if (foundation[i].size() != RANKS) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /** True when {@code pos} starts a face-up, correctly-ordered run. */
    public boolean isMovableRun(int p, int pos) {
        if (pos < tabUp[p] || pos >= tableau[p].size()) {
            return false;
        }
        for (int i = pos; i < tableau[p].size() - 1; i++) {
            int a = tableau[p].get(i);
            int b = tableau[p].get(i + 1);
            if (isRed(a) == isRed(b) || rank(b) != rank(a) - 1) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code card} may be placed on tableau pile {@code dest}. */
    public boolean canStackOnTableau(int card, int dest) {
        ArrayList<Integer> pile = tableau[dest];
        if (pile.isEmpty()) {
            return rank(card) == 12;          // only a King fills an empty column
        }
        int top = pile.get(pile.size() - 1);
        return isFaceUp(dest, pile.size() - 1)
                && isRed(card) != isRed(top)
                && rank(card) == rank(top) - 1;
    }

    /** True when {@code card} may be placed on foundation {@code f}. */
    public boolean canPlaceOnFoundation(int card, int f) {
        ArrayList<Integer> pile = foundation[f];
        if (pile.isEmpty()) {
            return rank(card) == 0;           // Ace starts a foundation
        }
        int top = pile.get(pile.size() - 1);
        return suit(card) == suit(top) && rank(card) == rank(top) + 1;
    }

    /** Foundation index that accepts {@code card}, or -1. */
    public int foundationFor(int card) {
        for (int f = 0; f < SUITS; f++) {
            if (canPlaceOnFoundation(card, f)) {
                return f;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Draws one card from the stock, or recycles the waste when it is empty. */
    public boolean drawStock() {
        if (stock.isEmpty() && waste.isEmpty()) {
            return false;
        }
        pushUndo();
        if (!stock.isEmpty()) {
            waste.add(stock.remove(stock.size() - 1));
        } else {
            while (!waste.isEmpty()) {
                stock.add(waste.remove(waste.size() - 1));
            }
        }
        moves++;
        return true;
    }

    /** Moves the waste's top card to foundation {@code f}. */
    public boolean moveWasteToFoundation(int f) {
        int card = wasteTop();
        if (card < 0 || !canPlaceOnFoundation(card, f)) {
            return false;
        }
        pushUndo();
        waste.remove(waste.size() - 1);
        foundation[f].add(card);
        moves++;
        return true;
    }

    /** Moves a tableau pile's top card to foundation {@code f}. */
    public boolean moveTableauToFoundation(int p, int f) {
        ArrayList<Integer> pile = tableau[p];
        if (pile.isEmpty()) {
            return false;
        }
        int pos = pile.size() - 1;
        int card = pile.get(pos);
        if (!isFaceUp(p, pos) || !canPlaceOnFoundation(card, f)) {
            return false;
        }
        pushUndo();
        pile.remove(pos);
        foundation[f].add(card);
        flipTop(p);
        moves++;
        return true;
    }

    /** Moves the run at {@code src[srcPos..]} onto tableau pile {@code dest}. */
    public boolean moveTableauToTableau(int src, int srcPos, int dest) {
        if (src == dest || !isMovableRun(src, srcPos)) {
            return false;
        }
        int card = tableau[src].get(srcPos);
        if (!canStackOnTableau(card, dest)) {
            return false;
        }
        pushUndo();
        List<Integer> run = new ArrayList<Integer>(
                tableau[src].subList(srcPos, tableau[src].size()));
        for (int i = tableau[src].size() - 1; i >= srcPos; i--) {
            tableau[src].remove(i);
        }
        tableau[dest].addAll(run);
        flipTop(src);
        moves++;
        return true;
    }

    /** Moves the waste's top card onto tableau pile {@code dest}. */
    public boolean moveWasteToTableau(int dest) {
        int card = wasteTop();
        if (card < 0 || !canStackOnTableau(card, dest)) {
            return false;
        }
        pushUndo();
        waste.remove(waste.size() - 1);
        tableau[dest].add(card);
        moves++;
        return true;
    }

    /** Sends the waste's top card to whichever foundation accepts it. */
    public boolean sendWasteToFoundation() {
        int card = wasteTop();
        if (card < 0) {
            return false;
        }
        int f = foundationFor(card);
        return f >= 0 && moveWasteToFoundation(f);
    }

    /** Sends a tableau pile's top card to whichever foundation accepts it. */
    public boolean sendTableauToFoundation(int p) {
        if (tableau[p].isEmpty()) {
            return false;
        }
        int pos = tableau[p].size() - 1;
        if (!isFaceUp(p, pos)) {
            return false;
        }
        int f = foundationFor(tableau[p].get(pos));
        return f >= 0 && moveTableauToFoundation(p, f);
    }

    private void flipTop(int p) {
        int s = tableau[p].size();
        if (s > 0 && tabUp[p] >= s) {
            tabUp[p] = s - 1;
        }
    }

    /**
     * Greedily builds every available card onto the foundations until no more
     * moves exist (the classic "auto-finish"). Only safe once all tableau cards
     * are face-up, which the host checks.
     *
     * @return the number of cards moved
     */
    public int autoComplete() {
        int moved = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            int wt = wasteTop();
            if (wt >= 0) {
                int f = foundationFor(wt);
                if (f >= 0 && moveWasteToFoundation(f)) {
                    moved++;
                    progress = true;
                }
            }
            for (int p = 0; p < TABLEAU; p++) {
                if (tableau[p].isEmpty()) {
                    continue;
                }
                int pos = tableau[p].size() - 1;
                if (!isFaceUp(p, pos)) {
                    continue;
                }
                int card = tableau[p].get(pos);
                int f = foundationFor(card);
                if (f >= 0 && moveTableauToFoundation(p, f)) {
                    moved++;
                    progress = true;
                }
            }
        }
        return moved;
    }

    /** True when every tableau card is face-up and the stock is exhausted. */
    public boolean canAutoComplete() {
        if (!stock.isEmpty()) {
            return false;
        }
        for (int p = 0; p < TABLEAU; p++) {
            if (tabUp[p] != 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    public boolean undo() {
        Snapshot s = undoStack.pollLast();
        if (s == null) {
            return false;
        }
        s.restore();
        moves = Math.max(0, moves - 1);
        return true;
    }

    private void pushUndo() {
        undoStack.addLast(new Snapshot());
        if (undoStack.size() > 500) {
            undoStack.pollFirst();
        }
    }

    /** A deep copy of the mutable state, for one-step-per-move undo. */
    private final class Snapshot {
        private final ArrayList<Integer> stockC = new ArrayList<Integer>(stock);
        private final ArrayList<Integer> wasteC = new ArrayList<Integer>(waste);
        private final ArrayList<Integer>[] foundC = copyPiles(foundation);
        private final ArrayList<Integer>[] tabC = copyPiles(tableau);
        private final int[] tabUpC = tabUp.clone();

        @SuppressWarnings("unchecked")
        private ArrayList<Integer>[] copyPiles(ArrayList<Integer>[] src) {
            ArrayList<Integer>[] out = new ArrayList[src.length];
            for (int i = 0; i < src.length; i++) {
                out[i] = new ArrayList<Integer>(src[i]);
            }
            return out;
        }

        void restore() {
            stock.clear();
            stock.addAll(stockC);
            waste.clear();
            waste.addAll(wasteC);
            for (int i = 0; i < SUITS; i++) {
                foundation[i].clear();
                foundation[i].addAll(foundC[i]);
            }
            for (int i = 0; i < TABLEAU; i++) {
                tableau[i].clear();
                tableau[i].addAll(tabC[i]);
                tabUp[i] = tabUpC[i];
            }
        }
    }

    // ------------------------------------------------------------------
    // Hint
    // ------------------------------------------------------------------

    /** A suggested move; {@code srcPos} is -1 unless the source is a tableau. */
    public static final class Hint {
        public final int srcZone;
        public final int srcPile;
        public final int srcPos;
        public final int card;
        public final int destZone;
        public final int destIndex;

        Hint(int srcZone, int srcPile, int srcPos, int card,
                int destZone, int destIndex) {
            this.srcZone = srcZone;
            this.srcPile = srcPile;
            this.srcPos = srcPos;
            this.card = card;
            this.destZone = destZone;
            this.destIndex = destIndex;
        }
    }

    /** Returns one legal, useful move, or {@code null} if the stock must be drawn. */
    public Hint hint() {
        // Waste -> foundation.
        int wt = wasteTop();
        if (wt >= 0) {
            int f = foundationFor(wt);
            if (f >= 0) {
                return new Hint(ZONE_WASTE, -1, -1, wt, ZONE_FOUNDATION, f);
            }
        }
        // Tableau -> foundation.
        for (int p = 0; p < TABLEAU; p++) {
            if (tableau[p].isEmpty()) {
                continue;
            }
            int pos = tableau[p].size() - 1;
            if (isFaceUp(p, pos)) {
                int card = tableau[p].get(pos);
                int f = foundationFor(card);
                if (f >= 0) {
                    return new Hint(ZONE_TABLEAU, p, pos, card, ZONE_FOUNDATION, f);
                }
            }
        }
        // Tableau -> tableau (prefer a move that exposes a face-down card).
        Hint fallback = null;
        for (int src = 0; src < TABLEAU; src++) {
            for (int pos = tabUp[src]; pos < tableau[src].size(); pos++) {
                if (!isMovableRun(src, pos)) {
                    break;
                }
                int card = tableau[src].get(pos);
                for (int dest = 0; dest < TABLEAU; dest++) {
                    if (dest == src || !canStackOnTableau(card, dest)) {
                        continue;
                    }
                    Hint h = new Hint(ZONE_TABLEAU, src, pos, card,
                            ZONE_TABLEAU, dest);
                    if (pos > tabUp[src]) {
                        return h;            // uncovers a hidden card: strongest
                    }
                    if (fallback == null && !tableau[dest].isEmpty()) {
                        fallback = h;        // avoid pointless empty<->empty shuffles
                    }
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        // Waste -> tableau.
        if (wt >= 0) {
            for (int dest = 0; dest < TABLEAU; dest++) {
                if (canStackOnTableau(wt, dest)) {
                    return new Hint(ZONE_WASTE, -1, -1, wt, ZONE_TABLEAU, dest);
                }
            }
        }
        return null;
    }
}
