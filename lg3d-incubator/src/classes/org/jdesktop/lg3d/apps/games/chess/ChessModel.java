/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.chess;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A complete chess engine: legal move generation (including castling, en
 * passant and promotion), check / checkmate / stalemate / insufficient-material
 * detection, and a negamax + alpha-beta opponent with a quiescence search and
 * the standard simplified-evaluation piece-square tables.
 *
 * <p>The engine is pure Java (no lg3d / AWT dependency) so it can be exercised
 * headlessly. The board is an {@code int[64]} indexed {@code row * 8 + col}
 * with {@code row 0} = rank 8 (Black's back rank) and {@code col 0} = file a.
 * Pieces are signed: positive = White, negative = Black; the magnitude is one of
 * {@link #PAWN} / {@link #KNIGHT} / {@link #BISHOP} / {@link #ROOK} /
 * {@link #QUEEN} / {@link #KING}. White always moves first and the human plays
 * White; the AI plays Black.</p>
 */
public class ChessModel {

    public static final int PAWN = 1, KNIGHT = 2, BISHOP = 3,
            ROOK = 4, QUEEN = 5, KING = 6;

    public static final int WHITE = 1, BLACK = -1, NONE = 0;

    private static final int MATE = 1_000_000;

    // Castling-right bits.
    private static final int WK = 1, WQ = 2, BK = 4, BQ = 8;

    /** A position: board, side to move, castling rights and ep target. */
    public static final class Pos {
        final int[] b = new int[64];
        boolean white;
        int castle;
        int ep = -1;
        int halfmove;

        Pos copy() {
            Pos p = new Pos();
            System.arraycopy(b, 0, p.b, 0, 64);
            p.white = white;
            p.castle = castle;
            p.ep = ep;
            p.halfmove = halfmove;
            return p;
        }
    }

    /** A move; {@code promo} is a piece type (or 0), {@code captured} the victim. */
    public static final class Move {
        public final int from;
        public final int to;
        public final int promo;
        public final int captured;

        Move(int from, int to, int promo, int captured) {
            this.from = from;
            this.to = to;
            this.promo = promo;
            this.captured = captured;
        }

        public int getFrom() { return from; }
        public int getTo() { return to; }
        public int getPromo() { return promo; }
    }

    private static final int[] MATERIAL = { 0, 100, 320, 330, 500, 900, 20000 };

    private static final int[][] PST = {
        {}, // placeholder index 0 (unused)
        { // pawn
             0,  0,  0,  0,  0,  0,  0,  0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
             5,  5, 10, 25, 25, 10,  5,  5,
             0,  0,  0, 20, 20,  0,  0,  0,
             5, -5,-10,  0,  0,-10, -5,  5,
             5, 10, 10,-20,-20, 10, 10,  5,
             0,  0,  0,  0,  0,  0,  0,  0 },
        { // knight
           -50,-40,-30,-30,-30,-30,-40,-50,
           -40,-20,  0,  0,  0,  0,-20,-40,
           -30,  0, 10, 15, 15, 10,  0,-30,
           -30,  5, 15, 20, 20, 15,  5,-30,
           -30,  0, 15, 20, 20, 15,  0,-30,
           -30,  5, 10, 15, 15, 10,  5,-30,
           -40,-20,  0,  5,  5,  0,-20,-40,
           -50,-40,-30,-30,-30,-30,-40,-50 },
        { // bishop
           -20,-10,-10,-10,-10,-10,-10,-20,
           -10,  0,  0,  0,  0,  0,  0,-10,
           -10,  0,  5, 10, 10,  5,  0,-10,
           -10,  5,  5, 10, 10,  5,  5,-10,
           -10,  0, 10, 10, 10, 10,  0,-10,
           -10, 10, 10, 10, 10, 10, 10,-10,
           -10,  5,  0,  0,  0,  0,  5,-10,
           -20,-10,-10,-10,-10,-10,-10,-20 },
        { // rook
             0,  0,  0,  0,  0,  0,  0,  0,
             5, 10, 10, 10, 10, 10, 10,  5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
            -5,  0,  0,  0,  0,  0,  0, -5,
             0,  0,  0,  5,  5,  0,  0,  0 },
        { // queen
           -20,-10,-10, -5, -5,-10,-10,-20,
           -10,  0,  0,  0,  0,  0,  0,-10,
           -10,  0,  5,  5,  5,  5,  0,-10,
            -5,  0,  5,  5,  5,  5,  0, -5,
             0,  0,  5,  5,  5,  5,  0, -5,
           -10,  5,  5,  5,  5,  5,  0,-10,
           -10,  0,  5,  0,  0,  0,  0,-10,
           -20,-10,-10, -5, -5,-10,-10,-20 },
        { // king (middlegame)
           -30,-40,-40,-50,-50,-40,-40,-30,
           -30,-40,-40,-50,-50,-40,-40,-30,
           -30,-40,-40,-50,-50,-40,-40,-30,
           -30,-40,-40,-50,-50,-40,-40,-30,
           -20,-30,-30,-40,-40,-30,-30,-20,
           -10,-20,-20,-20,-20,-20,-20,-10,
            20, 20,  0,  0,  0,  0, 20, 20,
            20, 30, 10,  0,  0, 10, 30, 20 },
    };

    private static final int[][] KNIGHT_DELTAS = {
        {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
        {1, -2}, {1, 2}, {2, -1}, {2, 1},
    };
    private static final int[][] BISHOP_DELTAS = {
        {-1, -1}, {-1, 1}, {1, -1}, {1, 1},
    };
    private static final int[][] ROOK_DELTAS = {
        {-1, 0}, {1, 0}, {0, -1}, {0, 1},
    };
    private static final int[][] KING_DELTAS = {
        {-1, -1}, {-1, 0}, {-1, 1}, {0, -1},
        {0, 1}, {1, -1}, {1, 0}, {1, 1},
    };

    private Pos pos;
    private final Deque<Pos> history = new ArrayDeque<Pos>();
    private Move lastMove;
    private int depth = 3;

    public ChessModel() {
        newGame();
    }

    public void newGame() {
        pos = new Pos();
        int[] back = { ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK };
        for (int c = 0; c < 8; c++) {
            pos.b[c] = -back[c];           // rank 8
            pos.b[8 + c] = -PAWN;          // rank 7
            pos.b[48 + c] = PAWN;          // rank 2
            pos.b[56 + c] = back[c];       // rank 1
        }
        pos.white = true;
        pos.castle = WK | WQ | BK | BQ;
        pos.ep = -1;
        pos.halfmove = 0;
        history.clear();
        lastMove = null;
    }

    public void setDepth(int depth) {
        this.depth = Math.max(1, depth);
    }

    // ------------------------------------------------------------------
    // Accessors for the view / host
    // ------------------------------------------------------------------

    public int pieceAt(int idx) {
        return pos.b[idx];
    }

    public boolean whiteToMove() {
        return pos.white;
    }

    public Move getLastMove() {
        return lastMove;
    }

    public int sideOf(int idx) {
        int p = pos.b[idx];
        return p == 0 ? NONE : (p > 0 ? WHITE : BLACK);
    }

    /** Legal moves for the piece on {@code idx} (empty when not ours to move). */
    public List<Move> legalMovesFrom(int idx) {
        List<Move> out = new ArrayList<Move>();
        int p = pos.b[idx];
        if (p == 0 || (p > 0) != pos.white) {
            return out;
        }
        for (Move m : generateLegal(pos)) {
            if (m.from == idx) {
                out.add(m);
            }
        }
        return out;
    }

    public boolean inCheck() {
        return isAttacked(pos, kingSquare(pos, pos.white), !pos.white);
    }

    public boolean isCheckmate() {
        return inCheck() && generateLegal(pos).isEmpty();
    }

    public boolean isStalemate() {
        return !inCheck() && generateLegal(pos).isEmpty();
    }

    public boolean isGameOver() {
        return isCheckmate() || isStalemate() || isInsufficientMaterial()
                || pos.halfmove >= 100;
    }

    /** WHITE / BLACK for the winner, or NONE for a draw / ongoing game. */
    public int getWinner() {
        if (isCheckmate()) {
            return pos.white ? BLACK : WHITE;   // side to move is mated
        }
        return NONE;
    }

    public boolean isDraw() {
        return isGameOver() && !isCheckmate();
    }

    /** True when it is the human's (White's) turn and the game is live. */
    public boolean isHumanTurn() {
        return pos.white && !isGameOver();
    }

    // ------------------------------------------------------------------
    // Making moves
    // ------------------------------------------------------------------

    /**
     * Applies a human move if {@code from->to} is legal.
     *
     * @return the applied move, or {@code null} when illegal
     */
    public Move makeHumanMove(int from, int to) {
        if (!pos.white || isGameOver()) {
            return null;
        }
        for (Move m : generateLegal(pos)) {
            if (m.from == from && m.to == to) {
                pushHistory();
                apply(pos, m);
                lastMove = m;
                return m;
            }
        }
        return null;
    }

    /** Lets the AI (Black) reply; no-op unless it is Black's turn. */
    public Move aiMove() {
        if (pos.white || isGameOver()) {
            return null;
        }
        Move m = bestMove(pos, depth);
        if (m == null) {
            return null;
        }
        pushHistory();
        apply(pos, m);
        lastMove = m;
        return m;
    }

    /** Undoes one half-move (used to roll back the AI reply + human move). */
    public boolean undo() {
        Pos prev = history.pollLast();
        if (prev == null) {
            return false;
        }
        pos = prev;
        // Rebuild lastMove roughly: it is unknown after a pop, so clear it.
        lastMove = null;
        return true;
    }

    private void pushHistory() {
        history.addLast(pos.copy());
        if (history.size() > 400) {
            history.pollFirst();
        }
    }

    // ------------------------------------------------------------------
    // Move application
    // ------------------------------------------------------------------

    private void apply(Pos p, Move m) {
        int piece = p.b[m.from];
        int type = Math.abs(piece);
        boolean white = piece > 0;

        p.b[m.from] = 0;

        // En-passant capture removes the pawn beside the destination.
        if (type == PAWN && m.to == p.ep && p.ep >= 0) {
            p.b[white ? m.to + 8 : m.to - 8] = 0;
        }

        // Place the (possibly promoted) piece.
        p.b[m.to] = (m.promo != 0) ? (white ? m.promo : -m.promo) : piece;

        // Castling rook hop.
        if (type == KING && Math.abs(col(m.to) - col(m.from)) == 2) {
            int r = row(m.from) * 8;
            if (col(m.to) == 6) {              // king side
                p.b[r + 5] = p.b[r + 7];
                p.b[r + 7] = 0;
            } else {                            // queen side
                p.b[r + 3] = p.b[r + 0];
                p.b[r + 0] = 0;
            }
        }

        // Castling rights.
        if (type == KING) {
            p.castle &= white ? ~(WK | WQ) : ~(BK | BQ);
        }
        if (m.from == 63 || m.to == 63) p.castle &= ~WK;
        if (m.from == 56 || m.to == 56) p.castle &= ~WQ;
        if (m.from == 7 || m.to == 7) p.castle &= ~BK;
        if (m.from == 0 || m.to == 0) p.castle &= ~BQ;

        // En-passant target square.
        p.ep = -1;
        if (type == PAWN && Math.abs(row(m.to) - row(m.from)) == 2) {
            p.ep = idx((row(m.from) + row(m.to)) / 2, col(m.from));
        }

        // Half-move clock (50-move rule).
        if (type == PAWN || m.captured != 0) {
            p.halfmove = 0;
        } else {
            p.halfmove++;
        }

        p.white = !p.white;
    }

    // ------------------------------------------------------------------
    // Move generation
    // ------------------------------------------------------------------

    private List<Move> generateLegal(Pos p) {
        List<Move> pseudo = generatePseudo(p);
        List<Move> legal = new ArrayList<Move>(pseudo.size());
        for (Move m : pseudo) {
            Pos q = p.copy();
            apply(q, m);
            int kingSq = kingSquare(q, p.white);   // mover's king after the move
            if (kingSq >= 0 && !isAttacked(q, kingSq, !p.white)) {
                legal.add(m);
            }
        }
        return legal;
    }

    private List<Move> generatePseudo(Pos p) {
        List<Move> moves = new ArrayList<Move>();
        for (int from = 0; from < 64; from++) {
            int piece = p.b[from];
            if (piece == 0 || (piece > 0) != p.white) {
                continue;
            }
            int type = Math.abs(piece);
            switch (type) {
                case PAWN: genPawn(p, from, piece > 0, moves); break;
                case KNIGHT: genStep(p, from, piece, KNIGHT_DELTAS, moves); break;
                case BISHOP: genSlide(p, from, piece, BISHOP_DELTAS, moves); break;
                case ROOK: genSlide(p, from, piece, ROOK_DELTAS, moves); break;
                case QUEEN:
                    genSlide(p, from, piece, BISHOP_DELTAS, moves);
                    genSlide(p, from, piece, ROOK_DELTAS, moves);
                    break;
                case KING:
                    genStep(p, from, piece, KING_DELTAS, moves);
                    genCastling(p, from, piece > 0, moves);
                    break;
                default: break;
            }
        }
        return moves;
    }

    private void genPawn(Pos p, int from, boolean white, List<Move> moves) {
        int r = row(from), c = col(from);
        int dir = white ? -1 : 1;
        int startRow = white ? 6 : 1;
        int promoRow = white ? 0 : 7;

        int oneR = r + dir;
        if (oneR >= 0 && oneR < 8 && p.b[idx(oneR, c)] == 0) {
            addPawn(moves, from, idx(oneR, c), white, oneR == promoRow);
            int twoR = r + 2 * dir;
            if (r == startRow && p.b[idx(twoR, c)] == 0) {
                moves.add(new Move(from, idx(twoR, c), 0, 0));
            }
        }
        for (int dc = -1; dc <= 1; dc += 2) {
            int nc = c + dc;
            if (oneR < 0 || oneR > 7 || nc < 0 || nc > 7) {
                continue;
            }
            int to = idx(oneR, nc);
            int target = p.b[to];
            if (target != 0 && (target > 0) != white) {
                addPawnCapture(moves, from, to, target, white, oneR == promoRow);
            } else if (to == p.ep && p.ep >= 0) {
                // En-passant: the captured pawn sits behind the target square.
                moves.add(new Move(from, to, 0, white ? -PAWN : PAWN));
            }
        }
    }

    private void addPawn(List<Move> moves, int from, int to, boolean white,
            boolean promo) {
        if (promo) {
            moves.add(new Move(from, to, QUEEN, 0));
        } else {
            moves.add(new Move(from, to, 0, 0));
        }
    }

    private void addPawnCapture(List<Move> moves, int from, int to, int captured,
            boolean white, boolean promo) {
        if (promo) {
            moves.add(new Move(from, to, QUEEN, captured));
        } else {
            moves.add(new Move(from, to, 0, captured));
        }
    }

    private void genStep(Pos p, int from, int piece, int[][] deltas,
            List<Move> moves) {
        int r = row(from), c = col(from);
        boolean white = piece > 0;
        for (int[] d : deltas) {
            int nr = r + d[0], nc = c + d[1];
            if (nr < 0 || nr > 7 || nc < 0 || nc > 7) {
                continue;
            }
            int to = idx(nr, nc);
            int target = p.b[to];
            if (target == 0) {
                moves.add(new Move(from, to, 0, 0));
            } else if ((target > 0) != white) {
                moves.add(new Move(from, to, 0, target));
            }
        }
    }

    private void genSlide(Pos p, int from, int piece, int[][] deltas,
            List<Move> moves) {
        int r = row(from), c = col(from);
        boolean white = piece > 0;
        for (int[] d : deltas) {
            int nr = r + d[0], nc = c + d[1];
            while (nr >= 0 && nr < 8 && nc >= 0 && nc < 8) {
                int to = idx(nr, nc);
                int target = p.b[to];
                if (target == 0) {
                    moves.add(new Move(from, to, 0, 0));
                } else {
                    if ((target > 0) != white) {
                        moves.add(new Move(from, to, 0, target));
                    }
                    break;
                }
                nr += d[0];
                nc += d[1];
            }
        }
    }

    private void genCastling(Pos p, int from, boolean white, List<Move> moves) {
        if (white) {
            if (from != 60) return;
            if ((p.castle & WK) != 0 && p.b[61] == 0 && p.b[62] == 0
                    && p.b[63] == ROOK
                    && !isAttacked(p, 60, false) && !isAttacked(p, 61, false)
                    && !isAttacked(p, 62, false)) {
                moves.add(new Move(60, 62, 0, 0));
            }
            if ((p.castle & WQ) != 0 && p.b[59] == 0 && p.b[58] == 0
                    && p.b[57] == 0 && p.b[56] == ROOK
                    && !isAttacked(p, 60, false) && !isAttacked(p, 59, false)
                    && !isAttacked(p, 58, false)) {
                moves.add(new Move(60, 58, 0, 0));
            }
        } else {
            if (from != 4) return;
            if ((p.castle & BK) != 0 && p.b[5] == 0 && p.b[6] == 0
                    && p.b[7] == -ROOK
                    && !isAttacked(p, 4, true) && !isAttacked(p, 5, true)
                    && !isAttacked(p, 6, true)) {
                moves.add(new Move(4, 6, 0, 0));
            }
            if ((p.castle & BQ) != 0 && p.b[3] == 0 && p.b[2] == 0
                    && p.b[1] == 0 && p.b[0] == -ROOK
                    && !isAttacked(p, 4, true) && !isAttacked(p, 3, true)
                    && !isAttacked(p, 2, true)) {
                moves.add(new Move(4, 2, 0, 0));
            }
        }
    }

    // ------------------------------------------------------------------
    // Attack detection
    // ------------------------------------------------------------------

    private boolean isAttacked(Pos p, int sq, boolean byWhite) {
        int r = row(sq), c = col(sq);
        int pawn = byWhite ? PAWN : -PAWN;
        // Pawns: a white pawn attacks the square diagonally in front of it,
        // i.e. from (r+1, c+-1) toward lower rows.
        int pr = byWhite ? r + 1 : r - 1;
        for (int dc = -1; dc <= 1; dc += 2) {
            if (onBoard(pr, c + dc) && p.b[idx(pr, c + dc)] == pawn) {
                return true;
            }
        }
        int knight = byWhite ? KNIGHT : -KNIGHT;
        for (int[] d : KNIGHT_DELTAS) {
            int nr = r + d[0], nc = c + d[1];
            if (onBoard(nr, nc) && p.b[idx(nr, nc)] == knight) {
                return true;
            }
        }
        int king = byWhite ? KING : -KING;
        for (int[] d : KING_DELTAS) {
            int nr = r + d[0], nc = c + d[1];
            if (onBoard(nr, nc) && p.b[idx(nr, nc)] == king) {
                return true;
            }
        }
        int bishop = byWhite ? BISHOP : -BISHOP;
        int rook = byWhite ? ROOK : -ROOK;
        int queen = byWhite ? QUEEN : -QUEEN;
        for (int[] d : BISHOP_DELTAS) {
            int nr = r + d[0], nc = c + d[1];
            while (onBoard(nr, nc)) {
                int pc = p.b[idx(nr, nc)];
                if (pc != 0) {
                    if (pc == bishop || pc == queen) return true;
                    break;
                }
                nr += d[0];
                nc += d[1];
            }
        }
        for (int[] d : ROOK_DELTAS) {
            int nr = r + d[0], nc = c + d[1];
            while (onBoard(nr, nc)) {
                int pc = p.b[idx(nr, nc)];
                if (pc != 0) {
                    if (pc == rook || pc == queen) return true;
                    break;
                }
                nr += d[0];
                nc += d[1];
            }
        }
        return false;
    }

    private int kingSquare(Pos p, boolean white) {
        int king = white ? KING : -KING;
        for (int i = 0; i < 64; i++) {
            if (p.b[i] == king) {
                return i;
            }
        }
        return -1;
    }

    private boolean isInsufficientMaterial() {
        int bishops = 0, knights = 0, others = 0;
        for (int i = 0; i < 64; i++) {
            int t = Math.abs(pos.b[i]);
            if (t == KING) continue;
            if (t == BISHOP) bishops++;
            else if (t == KNIGHT) knights++;
            else others++;
        }
        return others == 0 && (bishops + knights) <= 1;
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** Returns the AI's best move for the side to move in {@code root}. */
    public Move bestMove(Pos root, int maxDepth) {
        List<Move> moves = generateLegal(root);
        if (moves.isEmpty()) {
            return null;
        }
        orderMoves(moves);
        Move best = null;
        int alpha = -MATE * 2;
        int beta = MATE * 2;
        for (Move m : moves) {
            Pos q = root.copy();
            apply(q, m);
            int score = -search(q, maxDepth - 1, -beta, -alpha, 1);
            if (best == null || score > alpha) {
                alpha = Math.max(alpha, score);
                best = m;
            }
        }
        return best;
    }

    private int search(Pos p, int depth, int alpha, int beta, int ply) {
        if (depth <= 0) {
            return quiesce(p, alpha, beta, ply);
        }
        List<Move> moves = generateLegal(p);
        if (moves.isEmpty()) {
            if (isAttacked(p, kingSquare(p, p.white), !p.white)) {
                return -(MATE - ply);      // checkmate
            }
            return 0;                       // stalemate
        }
        orderMoves(moves);
        for (Move m : moves) {
            Pos q = p.copy();
            apply(q, m);
            int score = -search(q, depth - 1, -beta, -alpha, ply + 1);
            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    private int quiesce(Pos p, int alpha, int beta, int ply) {
        int stand = eval(p);
        if (stand >= beta) {
            return beta;
        }
        if (stand > alpha) {
            alpha = stand;
        }
        List<Move> caps = new ArrayList<Move>();
        for (Move m : generateLegal(p)) {
            if (m.captured != 0 || m.promo != 0) {
                caps.add(m);
            }
        }
        orderMoves(caps);
        for (Move m : caps) {
            Pos q = p.copy();
            apply(q, m);
            int score = -quiesce(q, -beta, -alpha, ply + 1);
            if (score >= beta) {
                return beta;
            }
            if (score > alpha) {
                alpha = score;
            }
        }
        return alpha;
    }

    /** Captures first (MVV-LVA-ish), then promotions, to improve pruning. */
    private void orderMoves(List<Move> moves) {
        moves.sort((a, b) -> score(b) - score(a));
    }

    private int score(Move m) {
        int s = 0;
        if (m.captured != 0) {
            s += 10 * MATERIAL[Math.abs(m.captured)]
                    - MATERIAL[Math.abs(pos.b[m.from])];
        }
        if (m.promo != 0) {
            s += MATERIAL[m.promo];
        }
        return s;
    }

    /** Static evaluation from the perspective of the side to move. */
    private int eval(Pos p) {
        int score = 0;
        for (int i = 0; i < 64; i++) {
            int piece = p.b[i];
            if (piece == 0) {
                continue;
            }
            int type = Math.abs(piece);
            int val = MATERIAL[type];
            if (piece > 0) {
                score += val + PST[type][i];
            } else {
                score -= val + PST[type][i ^ 56];
            }
        }
        return p.white ? score : -score;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    static int idx(int r, int c) {
        return r * 8 + c;
    }

    static int row(int i) {
        return i >>> 3;
    }

    static int col(int i) {
        return i & 7;
    }

    static boolean onBoard(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
    }
}
