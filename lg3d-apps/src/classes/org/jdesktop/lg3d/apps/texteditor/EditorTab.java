/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Shape;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;
import javax.swing.undo.AbstractUndoableEdit;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;

/**
 * One open document: the editor's per-tab bundle of model ({@link
 * DefaultStyledDocument} + {@link MergingUndoManager} + file metadata) and view
 * ({@link JTextPane} in a scroll pane with a {@link LineNumberGutter} row
 * header). It owns every per-document editing behaviour:
 *
 * <ul>
 *   <li><b>Debounced syntax highlighting</b> &mdash; typing restarts a short
 *       non-repeating timer; the colouriser then repaints the whole document
 *       in one batch. Documents over
 *       {@link SyntaxHighlighter#MAX_HIGHLIGHT_CHARS} are left uncoloured so
 *       big files stay fast.</li>
 *   <li><b>Caret feedback</b> &mdash; the current line and the partner of a
 *       bracket at the caret are highlighted through the view highlighter
 *       (never through document attributes, so neither pollutes undo).</li>
 *   <li><b>Smart keys</b> &mdash; Enter continues the current indent and adds
 *       a level after a block opener ({@link SmartIndent}); Tab inserts one
 *       indent level, as spaces unless hard tabs are configured.</li>
 *   <li><b>Dirty tracking</b> &mdash; any insert/remove marks the tab dirty;
 *       loading and saving reset it. A {@code dirtyListener} lets the panel
 *       refresh the tab title.</li>
 *   <li><b>Single-step whole-text replace</b> &mdash;
 *       {@link #replaceWholeText} records one undoable edit capturing the old
 *       and new text, used by Replace All and by extensions.</li>
 * </ul>
 *
 * <p>No Java&nbsp;3D and no dialogs: the tab is a plain Swing component that
 * constructs and runs headless.</p>
 */
public final class EditorTab extends JPanel {

    /** Pause after the last keystroke before re-colouring. */
    private static final int HIGHLIGHT_DELAY_MS = 200;

    /** Tab stops are laid out for this many columns. */
    private static final int TAB_STOP_COLUMNS = 200;

    private final DefaultStyledDocument document = new DefaultStyledDocument();
    private final EditorPane textPane = new EditorPane(document);
    private final JScrollPane scrollPane;
    private final LineNumberGutter gutter;
    private final MergingUndoManager undo = new MergingUndoManager();
    private final Timer highlightTimer;

    private EditorSettings settings;
    private EditorTheme theme;
    private SimpleAttributeSet plainAttrs = new SimpleAttributeSet();

    private Path path;
    private Charset charset = StandardCharsets.UTF_8;
    private String eol = TextFileIO.EOL_LF;
    private Language language = Languages.PLAIN;
    private String untitledName = "Untitled";

    private boolean dirty;
    private boolean loading;
    private Runnable dirtyListener = () -> { };
    private Runnable caretListener = () -> { };
    private Runnable changeListener = () -> { };

    /** Highlighter tags for the current diagnostic squiggles. */
    private final List<Object> diagnosticTags = new ArrayList<>();
    /** 0-based line -> highest-severity diagnostic kind painted in the gutter. */
    private final Map<Integer, Diagnostic.Kind> diagnosticMarkers = new HashMap<>();
    private int diagnosticCount;

    private Object lineHighlightTag;
    private Object bracketTagA;
    private Object bracketTagB;

    /**
     * Creates an empty untitled tab styled by {@code settings}.
     */
    public EditorTab(EditorSettings settings) {
        super(new BorderLayout());
        this.settings = (settings != null) ? settings : EditorSettings.defaults();
        gutter = new LineNumberGutter(document, textPane);
        scrollPane = new JScrollPane(textPane,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setRowHeaderView(gutter);
        add(scrollPane, BorderLayout.CENTER);

        highlightTimer = new Timer(HIGHLIGHT_DELAY_MS,
                e -> applyHighlightNow());
        highlightTimer.setRepeats(false);

        document.addUndoableEditListener(undo);
        document.addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onContentEdit();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onContentEdit();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                // Attribute-only change (highlighting): never dirties.
            }
        });
        textPane.addCaretListener(e -> onCaretMoved());

        bindSmartKeys();
        // Initial styling records attribute edits: suppress and purge them so
        // a brand-new tab opens with a truly empty undo history.
        loading = true;
        try {
            applySettings(this.settings);
        } finally {
            loading = false;
        }
        undo.discardAllEdits();
    }

    // ------------------------------------------------------------------
    // Content and metadata
    // ------------------------------------------------------------------

    /** The underlying document; the panel's find bar edits through it. */
    public DefaultStyledDocument document() {
        return document;
    }

    /** The text component; exposed for caret work and test seams. */
    public JTextPane textPane() {
        return textPane;
    }

    /** The full document text ("" for an empty document). */
    public String getText() {
        try {
            return document.getText(0, document.getLength());
        } catch (BadLocationException ble) {
            return "";
        }
    }

    /**
     * Replaces the tab's content as loaded from / about to be written to a
     * file: undo history is discarded, the dirty flag cleared and the
     * language re-derived from the file name.
     */
    public void loadContent(String text, Path filePath, Charset cs,
            String lineEol) {
        loading = true;
        try {
            undo.closeBurst();
            document.removeUndoableEditListener(undo);
            document.replace(0, document.getLength(),
                    (text != null) ? text : "", plainAttrs);
            document.addUndoableEditListener(undo);
            undo.discardAllEdits();
        } catch (BadLocationException ble) {
            throw new IllegalStateException("document bounds", ble);
        } finally {
            loading = false;
        }
        this.path = filePath;
        if (cs != null) {
            this.charset = cs;
        }
        if (lineEol != null) {
            this.eol = lineEol;
        }
        this.language = Languages.forFileName(
                (filePath != null) ? filePath.getFileName().toString() : null);
        this.dirty = false;
        textPane.setCaretPosition(0);
        loading = true;
        try {
            applyHighlightNow();
            // Re-highlighting and the caret move record attribute edits:
            // purge them so "loading discards the undo history" holds.
            undo.discardAllEdits();
        } finally {
            loading = false;
        }
        gutter.refreshWidth();
        dirtyListener.run();
    }

    /**
     * Replaces the whole text as ONE undo step (Replace All, extensions).
     * A no-op when the text is unchanged.
     */
    public void replaceWholeText(String newText) {
        String text = (newText != null) ? newText : "";
        String old = getText();
        if (old.equals(text)) {
            return;
        }
        undo.closeBurst();
        try {
            document.removeUndoableEditListener(undo);
            document.replace(0, document.getLength(), text, plainAttrs);
            undo.addEdit(new WholeTextEdit(old, text));
            document.addUndoableEditListener(undo);
        } catch (BadLocationException ble) {
            document.addUndoableEditListener(undo);
            throw new IllegalStateException("document bounds", ble);
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Clears the dirty flag after a successful save. */
    public void markSaved() {
        dirty = false;
        dirtyListener.run();
    }

    public Path getPath() {
        return path;
    }

    /**
     * Rebinds the tab to {@code newPath} (used after a successful save or
     * Save As): re-derives the language from the new file name, re-highlights
     * and lets the panel refresh the tab title and status line.
     */
    public void setPath(Path newPath) {
        this.path = newPath;
        this.language = Languages.forFileName(
                (newPath != null && newPath.getFileName() != null)
                        ? newPath.getFileName().toString() : null);
        applyHighlightNow();
        dirtyListener.run();
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        if (charset != null) {
            this.charset = charset;
            dirty = true;
            dirtyListener.run();
        }
    }

    public String getEol() {
        return eol;
    }

    public void setEol(String eol) {
        if (TextFileIO.EOL_CRLF.equals(eol) || TextFileIO.EOL_LF.equals(eol)) {
            this.eol = eol;
            dirty = true;
            dirtyListener.run();
        }
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = (language != null) ? language : Languages.PLAIN;
        applyHighlightNow();
        dirtyListener.run();
    }

    /** The name shown on the tab: the file name, or the untitled label. */
    public String getDisplayName() {
        return (path != null && path.getFileName() != null)
                ? path.getFileName().toString() : untitledName;
    }

    public void setUntitledName(String name) {
        if (name != null && !name.isBlank()) {
            this.untitledName = name;
            dirtyListener.run();
        }
    }

    /** Hook the panel uses to refresh tab titles and the status line. */
    public void setDirtyListener(Runnable listener) {
        this.dirtyListener = (listener != null) ? listener : () -> { };
    }

    /** Hook the panel uses to refresh the caret status line. */
    public void setCaretListener(Runnable listener) {
        this.caretListener = (listener != null) ? listener : () -> { };
    }

    /**
     * Hook the panel uses to observe content edits (it debounces them before
     * notifying extensions through {@code onDocumentChanged}). Fires on the EDT
     * for every insert/remove once the tab is no longer loading.
     */
    public void setChangeListener(Runnable listener) {
        this.changeListener = (listener != null) ? listener : () -> { };
    }

    /**
     * Paints {@code diagnostics} on this tab: a gutter dot per line (highest
     * severity wins) and a wavy underline over each offending range. Replaces
     * any previous set. The document is not mutated.
     */
    public void setDiagnostics(List<Diagnostic> diagnostics) {
        clearDiagnostics();
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        Highlighter highlighter = textPane.getHighlighter();
        for (Diagnostic d : diagnostics) {
            if (d == null) {
                continue;
            }
            diagnosticCount++;
            int lineIdx = d.line() - 1;
            Diagnostic.Kind prev = diagnosticMarkers.get(lineIdx);
            if (prev == null || severityRank(d.kind()) > severityRank(prev)) {
                diagnosticMarkers.put(lineIdx, d.kind());
            }
            int p0 = offsetOf(d.line(), d.col());
            int p1 = offsetOf(d.endLineOrStart(), d.endColOrStart());
            if (p1 <= p0) {
                p1 = Math.min(document.getLength(), p0 + 1);
            }
            if (p1 > p0) {
                try {
                    diagnosticTags.add(highlighter.addHighlight(p0, p1,
                            new WavePainter(colorFor(d.kind()))));
                } catch (BadLocationException | RuntimeException ble) {
                    // Offsets fell outside a still-loading view; the gutter dot
                    // is painted next layout, so a dropped squiggle is harmless.
                }
            }
        }
        gutter.setMarkers(new HashMap<>(diagnosticMarkers));
    }

    /** Removes every diagnostic marker and underline from this tab. */
    public void clearDiagnostics() {
        Highlighter highlighter = textPane.getHighlighter();
        for (Object tag : diagnosticTags) {
            removeTag(highlighter, tag);
        }
        diagnosticTags.clear();
        diagnosticMarkers.clear();
        diagnosticCount = 0;
        gutter.clearMarkers();
    }

    /**
     * Toggles a breakpoint on the 1-based {@code line}; forwards to the gutter
     * so the debugger extension can round-trip breakpoint state.
     *
     * @return true when the line is now broken
     */
    public boolean toggleBreakpoint(int line) {
        int count = document.getDefaultRootElement().getElementCount();
        int target = Math.max(1, Math.min(line, count));
        return gutter.toggleBreakpoint(target - 1);
    }

    /** @return true when a breakpoint is set on the 1-based {@code line}. */
    public boolean hasBreakpoint(int line) {
        return gutter.hasBreakpoint(line - 1);
    }

    /** @return how many diagnostics are currently painted (test seam). */
    public int diagnosticCount() {
        return diagnosticCount;
    }

    /** @return the gutter marker kind on the 1-based {@code line}, or null. */
    public Diagnostic.Kind diagnosticAt(int line) {
        return diagnosticMarkers.get(line - 1);
    }

    /** Converts a 1-based line/col to a document offset, clamped to the text. */
    private int offsetOf(int line, int col) {
        Element root = document.getDefaultRootElement();
        int lineIdx = Math.max(0, Math.min(line - 1, root.getElementCount() - 1));
        int start = root.getElement(lineIdx).getStartOffset();
        int end = root.getElement(lineIdx).getEndOffset();
        int offset = start + Math.max(0, col - 1);
        return Math.min(offset, Math.max(start, end - 1));
    }

    /** Severity ordering: ERROR &gt; WARNING &gt; INFO &gt; HINT. */
    private static int severityRank(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> 3;
            case WARNING -> 2;
            case INFO -> 1;
            case HINT -> 0;
        };
    }

    private static Color colorFor(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> new Color(0xE0, 0x40, 0x40);
            case WARNING -> new Color(0xE0, 0xA0, 0x20);
            case INFO -> new Color(0x40, 0x90, 0xE0);
            case HINT -> new Color(0x90, 0x90, 0x90);
        };
    }

    /** A wavy-underline highlighter painter for a diagnostic range. */
    private static final class WavePainter implements Highlighter.HighlightPainter {
        private final Color color;

        WavePainter(Color color) {
            this.color = color;
        }

        @Override
        public void paint(Graphics g, int beginIndex, int endIndex,
                          Shape bounds, JTextComponent c) {
            Rectangle area = bounds.getBounds();
            Graphics g2 = g.create();
            try {
                g2.setColor(color);
                int w = 4;
                int h = Math.max(2, area.height / 3);
                int y = area.y + area.height - h;
                boolean up = true;
                for (int x = area.x; x < area.x + area.width; x += w) {
                    int seg = Math.min(w, area.x + area.width - x);
                    int y0 = up ? y : y - 1;
                    int y1 = up ? y - 1 : y;
                    g2.drawLine(x, y0, x + seg, y1);
                    up = !up;
                }
            } finally {
                g2.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    public boolean canUndo() {
        undo.closeBurst();
        return undo.canUndo();
    }

    public boolean canRedo() {
        undo.closeBurst();
        return undo.canRedo();
    }

    /** One undo step; typing bursts count as one thanks to the merger. */
    public void undo() {
        try {
            undo.undo();
        } catch (CannotUndoException cue) {
            // Nothing to undo: the toolbar state says so already.
        }
    }

    /** One redo step. */
    public void redo() {
        try {
            undo.redo();
        } catch (CannotRedoException cre) {
            // Nothing to redo.
        }
    }

    /** Drops the undo history (used after external content reloads). */
    public void discardUndo() {
        undo.discardAllEdits();
    }

    /**
     * Moves the caret to the start of 1-based {@code line} and scrolls it
     * into view. Out-of-range lines clamp to the first / last line.
     */
    public void goToLine(int line) {
        int count = document.getDefaultRootElement().getElementCount();
        int target = Math.max(1, Math.min(line, count));
        int offset = document.getDefaultRootElement().getElement(target - 1)
                .getStartOffset();
        textPane.setCaretPosition(offset);
        try {
            Rectangle rect = textPane.modelToView(offset);
            if (rect != null) {
                textPane.scrollRectToVisible(rect);
            }
        } catch (BadLocationException ble) {
            // Caret move alone is still useful.
        }
    }

    /** The 1-based line the caret is on. */
    public int getCaretLine() {
        return document.getDefaultRootElement()
                .getElementIndex(textPane.getCaretPosition()) + 1;
    }

    /** The 1-based column the caret is on. */
    public int getCaretColumn() {
        int caret = textPane.getCaretPosition();
        int lineStart = document.getDefaultRootElement()
                .getElementIndex(caret);
        int start = document.getDefaultRootElement().getElement(lineStart)
                .getStartOffset();
        return caret - start + 1;
    }

    /** The number of lines in the document (at least 1). */
    public int getLineCount() {
        return document.getDefaultRootElement().getElementCount();
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    /** The settings currently styling this tab. */
    public EditorSettings settings() {
        return settings;
    }

    /** The theme currently colouring this tab. */
    public EditorTheme theme() {
        return theme;
    }

    /**
     * Restyles the tab from {@code settings}: font, colours, wrap, gutter
     * visibility, tab stops, and a full re-highlight (or plain repaint when
     * highlighting is off).
     */
    public void applySettings(EditorSettings newSettings) {
        this.settings = (newSettings != null) ? newSettings
                : EditorSettings.defaults();
        this.theme = settings.getTheme();

        Font font = settings.getFont();
        textPane.setFont(font);
        textPane.setWrapping(settings.isWordWrap());
        textPane.setBackground(theme.getBackground());
        textPane.setForeground(theme.getForeground());
        textPane.setCaretColor(theme.getCaret());
        textPane.setSelectionColor(theme.getSelection());
        gutter.setColors(theme.getGutterBackground(),
                theme.getGutterForeground(), theme.getForeground());
        scrollPane.setRowHeaderView(settings.isLineNumbers() ? gutter : null);
        scrollPane.setBackground(theme.getBackground());

        plainAttrs = new SimpleAttributeSet();
        StyleConstants.setForeground(plainAttrs, theme.getForeground());
        StyleConstants.setFontFamily(plainAttrs, font.getFamily());
        StyleConstants.setFontSize(plainAttrs, font.getSize());
        applyTabStops(font);

        textPane.setCharacterAttributes(plainAttrs, false);
        applyHighlightNow();
        gutter.refreshWidth();
        onCaretMoved();
        revalidate();
        repaint();
    }

    /** Lays out hard-tab stops every {@code tabSize} columns. */
    private void applyTabStops(Font font) {
        FontMetrics fm = textPane.getFontMetrics(font);
        int width = Math.max(1, fm.charWidth(' ') * Math.max(1, settings.getTabSize()));
        TabStop[] stops = new TabStop[TAB_STOP_COLUMNS];
        for (int i = 0; i < stops.length; i++) {
            stops[i] = new TabStop((i + 1) * width);
        }
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setTabSet(attrs, new TabSet(stops));
        int length = document.getLength();
        document.setParagraphAttributes(0, Math.max(length, 1), attrs, false);
    }

    private void applyHighlightNow() {
        int length = document.getLength();
        boolean colourise = settings.isHighlight()
                && language != Languages.PLAIN
                && SyntaxHighlighter.shouldHighlight(length);
        if (!colourise) {
            if (length > 0) {
                document.setCharacterAttributes(0, length, plainAttrs, true);
            }
            return;
        }
        SyntaxHighlighter.apply(document, language, theme);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void onContentEdit() {
        if (loading) {
            return;
        }
        dirty = true;
        dirtyListener.run();
        changeListener.run();
        gutter.refreshWidth();
        if (settings.isHighlight()
                && SyntaxHighlighter.shouldHighlight(document.getLength())) {
            highlightTimer.restart();
        }
    }

    private void onCaretMoved() {
        int caret = textPane.getCaretPosition();
        gutter.setCaretLine(document.getDefaultRootElement().getElementIndex(caret));
        updateLineHighlight(caret);
        updateBracketHighlight(caret);
        caretListener.run();
    }

    private void updateLineHighlight(int caret) {
        Highlighter highlighter = textPane.getHighlighter();
        removeTag(highlighter, lineHighlightTag);
        lineHighlightTag = null;
        try {
            int line = document.getDefaultRootElement().getElementIndex(caret);
            int start = document.getDefaultRootElement().getElement(line)
                    .getStartOffset();
            int end = document.getDefaultRootElement().getElement(line)
                    .getEndOffset();
            lineHighlightTag = highlighter.addHighlight(start, end,
                    new DefaultHighlighter.DefaultHighlightPainter(
                            theme.getCurrentLine()));
        } catch (BadLocationException | RuntimeException rte) {
            // No highlight this round; the text stays perfectly usable.
        }
    }

    private void updateBracketHighlight(int caret) {
        Highlighter highlighter = textPane.getHighlighter();
        removeTag(highlighter, bracketTagA);
        removeTag(highlighter, bracketTagB);
        bracketTagA = null;
        bracketTagB = null;
        try {
            String text = getText();
            int partner = BracketMatcher.match(text, caret, language);
            if (partner < 0) {
                return;
            }
            int self = (caret < text.length()
                    && SmartIndent.isBracket(text.charAt(caret)))
                    ? caret : caret - 1;
            DefaultHighlighter.DefaultHighlightPainter painter =
                    new DefaultHighlighter.DefaultHighlightPainter(
                            theme.getBracketMatch());
            bracketTagA = highlighter.addHighlight(self, self + 1, painter);
            bracketTagB = highlighter.addHighlight(partner, partner + 1,
                    painter);
        } catch (BadLocationException | RuntimeException rte) {
            // Bracket highlighting is decorative only.
        }
    }

    private static void removeTag(Highlighter highlighter, Object tag) {
        if (tag != null) {
            try {
                highlighter.removeHighlight(tag);
            } catch (RuntimeException rte) {
                // Stale tag after a highlighter reset: ignore.
            }
        }
    }

    /** Enter continues the indent; Tab inserts one level. */
    private void bindSmartKeys() {
        javax.swing.InputMap input =
                textPane.getInputMap(WHEN_FOCUSED);
        javax.swing.ActionMap actions = textPane.getActionMap();
        input.put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ENTER, 0), "lg3d-smart-newline");
        actions.put("lg3d-smart-newline",
                new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        insertNewline();
                    }
                });
        input.put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_TAB, 0), "lg3d-indent");
        actions.put("lg3d-indent", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                insertIndent();
            }
        });
    }

    void insertNewline() {
        int caret = textPane.getCaretPosition();
        String indent = "";
        if (settings.isAutoIndent()) {
            try {
                int line = document.getDefaultRootElement().getElementIndex(caret);
                int start = document.getDefaultRootElement().getElement(line)
                        .getStartOffset();
                String current = document.getText(start, caret - start);
                indent = SmartIndent.nextLineIndent(current,
                        settings.getTabSize(), settings.isHardTabs());
            } catch (BadLocationException | RuntimeException ble) {
                indent = "";
            }
        }
        textPane.replaceSelection("\n" + indent);
    }

    void insertIndent() {
        // With a selection present the level replaces it (standard Tab
        // behaviour without block indent); without one it inserts.
        textPane.replaceSelection(SmartIndent.level(settings.getTabSize(),
                settings.isHardTabs()));
    }

    /** The text pane with a word-wrap switch (JTextPane always wraps). */
    private static final class EditorPane extends JTextPane {

        private boolean wrap = true;

        EditorPane(DefaultStyledDocument doc) {
            super(doc);
        }

        void setWrapping(boolean wrapping) {
            this.wrap = wrapping;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            if (wrap || getParent() == null) {
                return true;
            }
            // No wrap: grow past the viewport so the horizontal bar appears.
            return getUI().getPreferredSize(this).width
                    <= getParent().getWidth();
        }
    }

    /** One undoable whole-text swap (Replace All, extension transforms). */
    private final class WholeTextEdit extends AbstractUndoableEdit {

        private final String oldText;
        private final String newText;

        WholeTextEdit(String oldText, String newText) {
            this.oldText = oldText;
            this.newText = newText;
        }

        @Override
        public void undo() throws CannotUndoException {
            super.undo();
            swapTo(oldText);
        }

        @Override
        public void redo() throws CannotRedoException {
            super.redo();
            swapTo(newText);
        }

        private void swapTo(String text) {
            loading = true;
            try {
                document.removeUndoableEditListener(undo);
                document.replace(0, document.getLength(), text, plainAttrs);
                document.addUndoableEditListener(undo);
            } catch (BadLocationException ble) {
                document.addUndoableEditListener(undo);
                throw new CannotUndoException();
            } finally {
                loading = false;
            }
            dirty = true;
            dirtyListener.run();
        }
    }
}
