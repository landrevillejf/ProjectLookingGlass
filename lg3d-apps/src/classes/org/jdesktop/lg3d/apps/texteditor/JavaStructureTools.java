/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JOptionPane;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Java Structure" extension: the navigation and single-file
 * refactoring half of the Java/Kotlin IDE surface (Espresso Phase 2). It turns
 * the current document into an outline for the Structure tab, jumps the caret
 * to a symbol's definition, renames every occurrence of the identifier under
 * the caret in one undoable edit, and tidies the file's import block.
 *
 * <p><b>Java</b> is analysed with the running JDK's own compiler frontend:
 * {@link JavacTask#parse()} produces a real AST and {@link Trees} supplies
 * source positions, so outline lines and identifier occurrences are exact
 * (no string guessing inside comments or names). <b>Kotlin</b> has no AST
 * available in the JDK, so its outline is a documented best-effort
 * regex/indent scan; occurrences fall back to a word-boundary scan.</p>
 *
 * <p>Every analysis entry point is a pure static, headless-testable in
 * isolation; the only interactive dependency — the new-name prompt used by
 * rename — sits behind a seam so tests never touch Swing.</p>
 */
public final class JavaStructureTools implements TextEditorExtension {

    /** One import declaration line, capturing the optional {@code static} and the dotted name. */
    private static final Pattern IMPORT_LINE =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;\\s*$");

    /** Kotlin type declarations: class/interface/object/enum (with common modifiers). */
    private static final Pattern KOTLIN_TYPE =
            Pattern.compile("^\\s*(?:(?:public|private|internal|protected|open|abstract|final|sealed|data|value|inner)\\s+)*"
                    + "(class|interface|object)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    /** Kotlin function declarations (including member/extension forms). */
    private static final Pattern KOTLIN_FUN =
            Pattern.compile("^\\s*(?:(?:public|private|internal|protected|open|abstract|final|override|suspend|inline)\\s+)*"
                    + "fun\\s+(?:[\\w<>?, .]+\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*[(<]");

    /** Kotlin properties: top-level or indented class members. */
    private static final Pattern KOTLIN_PROPERTY =
            Pattern.compile("^\\s*(?:(?:public|private|internal|protected|lateinit|const)\\s+)*"
                    + "(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]");

    /** Java regex-outline fallback: types, methods and fields, best-effort. */
    private static final Pattern JAVA_TYPE_FALLBACK =
            Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|abstract|sealed|strictfp)\\s+)*"
                    + "(class|interface|enum|record|@interface)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern JAVA_METHOD_FALLBACK =
            Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|default|native)\\s+)*"
                    + "(?:<[^>]+>\\s*)?(?:[\\w<>., ?]|\\[)+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\([^;]*\\))?\\s*(?:throws [\\w, .]+)?[{;]");
    private static final Pattern JAVA_FIELD_FALLBACK =
            Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|transient|volatile)\\s+)+"
                    + "(?:[\\w<>., ?]|\\[)+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=[^;]*)?;");

    /** Asks the user for a replacement name; the seam keeps rename headless-testable. */
    interface RenamePrompt {
        /** @param current the identifier being renamed
         *  @return the new name, or null when the user cancelled */
        String ask(String current);
    }

    private static final RenamePrompt DIALOG_PROMPT = current -> {
        Object answer = JOptionPane.showInputDialog(
                (java.awt.Component) null,
                "New name for \"" + current + "\":");
        return (answer == null) ? null : answer.toString().trim();
    };

    private EditorContext editor;
    private DocumentContext currentDoc;
    private RenamePrompt prompt = DIALOG_PROMPT;

    // -- test seams --------------------------------------------------------

    final void setRenamePromptForTesting(RenamePrompt replacement) {
        this.prompt = (replacement != null) ? replacement : DIALOG_PROMPT;
    }

    // -- SPI ----------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = Set.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.DIAGNOSE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.java-structure",
                "Java Structure",
                "1.0.0",
                "Outline, go-to-definition, find occurrences, rename and organize imports for Java/Kotlin",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Java/Kotlin";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
        refreshOutline(doc);
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
        refreshOutline(doc);
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
        refreshOutline(doc);
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("struct-goto-definition", "Go to Definition",
                        "Jump the caret to the definition of the identifier under the caret",
                        this::goToDefinition),
                new ToolbarContribution("struct-find-occurrences", "Find Occurrences",
                        "List every line in this file that uses the identifier under the caret",
                        this::findOccurrences),
                new ToolbarContribution("struct-rename-symbol", "Rename Symbol",
                        "Rename every occurrence of the identifier under the caret (one undo step)",
                        this::renameSymbol),
                new ToolbarContribution("struct-organize-imports", "Organize Imports",
                        "Drop unused imports, remove duplicates and sort the import block",
                        this::organizeImportsAction)
        );
    }

    // -- outline --------------------------------------------------------------

    /** Publishes the outline for the document (or clears it for non-JVM files). */
    private void refreshOutline(DocumentContext doc) {
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        JavaDiagnosticsTools.Lang lang =
                JavaDiagnosticsTools.detectKind(doc.getFileName(), doc.getFullText());
        if (lang == JavaDiagnosticsTools.Lang.NONE) {
            ctx.showStructure(doc.getFilePath(), List.of());
            return;
        }
        List<StructureSymbol> symbols = (lang == JavaDiagnosticsTools.Lang.JAVA)
                ? outlineJava(doc.getFullText(), doc.getFileName())
                : outlineKotlin(doc.getFullText());
        ctx.showStructure(doc.getFilePath(), symbols);
    }

    /**
     * The outline of a Java source: a real AST from {@code JavacTask.parse()}
     * when the frontend is available, a documented best-effort regex scan
     * otherwise (broken sources included). Package-private static so tests can
     * pin its output without any Swing or editor wiring.
     */
    static List<StructureSymbol> outlineJava(String source, String fileName) {
        List<StructureSymbol> ast = outlineJavaAst(source, fileName);
        return (ast != null) ? ast : outlineJavaRegex(source);
    }

    /** AST outline via the compiler frontend; null when it is unavailable or fails. */
    private static List<StructureSymbol> outlineJavaAst(String source, String fileName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return null;
        }
        String name = (fileName == null || fileName.isBlank()) ? "Main.java" : fileName;
        try (StandardJavaFileManager fm =
                     compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { },
                    List.of("-proc:none"), null,
                    List.of(new JavaDiagnosticsTools.InMemorySource(name, source)));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            SourcePositions sp = Trees.instance(task).getSourcePositions();
            List<StructureSymbol> out = new ArrayList<>();
            for (CompilationUnitTree cu : units) {
                for (Tree decl : cu.getTypeDecls()) {
                    if (decl instanceof ClassTree cls) {
                        emitClass(cls, cu, sp, source, out, 0);
                    }
                }
            }
            return out;
        } catch (IOException | RuntimeException | Error re) {
            return null; // fall back to the regex outline
        }
    }

    /** Recursively emits a type and its members; nested types indent their names. */
    private static void emitClass(ClassTree cls, CompilationUnitTree cu,
                                  SourcePositions sp, String source,
                                  List<StructureSymbol> out, int depth) {
        String name = cls.getSimpleName().toString();
        if (name.isEmpty()) {
            return; // anonymous class: not a named outline entry
        }
        out.add(new StructureSymbol(indent(depth) + name, classKind(cls),
                lineOf(sp, cu, cls, source)));
        for (Tree member : cls.getMembers()) {
            if (member instanceof ClassTree nested) {
                emitClass(nested, cu, sp, source, out, depth + 1);
            } else if (member instanceof MethodTree method) {
                String mname = method.getName().toString();
                if (mname.isEmpty()) {
                    continue; // instance initializer
                }
                if ("<init>".equals(mname)) {
                    out.add(new StructureSymbol(indent(depth + 1) + name + "()",
                            "constructor", lineOf(sp, cu, method, source)));
                } else {
                    out.add(new StructureSymbol(indent(depth + 1) + mname,
                            "method", lineOf(sp, cu, method, source)));
                }
            } else if (member instanceof VariableTree field) {
                out.add(new StructureSymbol(indent(depth + 1) + field.getName().toString(),
                        "field", lineOf(sp, cu, field, source)));
            }
        }
    }

    private static String classKind(ClassTree cls) {
        return switch (cls.getKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case ANNOTATION_TYPE -> "annotation";
            case RECORD -> "record";
            default -> "class";
        };
    }

    private static int lineOf(SourcePositions sp, CompilationUnitTree cu, Tree tree, String source) {
        long pos = sp.getStartPosition(cu, tree);
        return (pos < 0) ? 1 : lineAtOffset(source, (int) pos) + 1;
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    /** Regex fallback outline for Java: same shape, best-effort positions. */
    static List<StructureSymbol> outlineJavaRegex(String source) {
        List<StructureSymbol> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("@")) {
                continue;
            }
            Matcher type = JAVA_TYPE_FALLBACK.matcher(lines[i]);
            Matcher method = lines[i].contains("(") ? JAVA_METHOD_FALLBACK.matcher(lines[i]) : null;
            Matcher field = lines[i].endsWith(";") ? JAVA_FIELD_FALLBACK.matcher(lines[i]) : null;
            if (type.find()) {
                out.add(new StructureSymbol(indent(depth) + type.group(2),
                        type.group(1).startsWith("@") ? "annotation" : type.group(1), i + 1));
            } else if (method != null && method.find() && !line.startsWith("return")
                    && !line.startsWith("if") && !line.startsWith("for")
                    && !line.startsWith("while") && !line.startsWith("switch")
                    && !line.startsWith("catch")) {
                out.add(new StructureSymbol(indent(depth + 1) + method.group(1), "method", i + 1));
            } else if (field != null && field.find()) {
                out.add(new StructureSymbol(indent(depth + 1) + field.group(1), "field", i + 1));
            }
        }
        return out;
    }

    /** Best-effort Kotlin outline: types, functions and properties, indented by depth. */
    static List<StructureSymbol> outlineKotlin(String source) {
        List<StructureSymbol> out = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        int depth = 0;
        boolean prevWasType = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            Matcher type = KOTLIN_TYPE.matcher(line);
            Matcher fun = KOTLIN_FUN.matcher(line);
            Matcher prop = KOTLIN_PROPERTY.matcher(line);
            if (type.find()) {
                out.add(new StructureSymbol(indent(depth) + type.group(2), type.group(1), i + 1));
                prevWasType = true;
            } else if (fun.find()) {
                out.add(new StructureSymbol(indent(depth + (prevWasType ? 1 : 0))
                        + fun.group(1), "function", i + 1));
            } else if (prop.find()) {
                out.add(new StructureSymbol(indent(depth + (prevWasType ? 1 : 0))
                        + prop.group(2), "property", i + 1));
            }
            if (trimmed.endsWith("{")) {
                depth++;
            } else if (trimmed.equals("}") || trimmed.startsWith("}")) {
                depth = Math.max(0, depth - 1);
                if (depth == 0) {
                    prevWasType = false;
                }
            }
        }
        return out;
    }

    // -- caret helpers ----------------------------------------------------------

    /** The Java/Kotlin identifier under {@code offset}, or "" when the caret is on punctuation. */
    static String identifierAt(String source, int offset) {
        if (source == null || source.isEmpty()
                || offset < 0 || offset > source.length()) {
            return "";
        }
        // Prefer the character right after the caret; fall back to the one before it,
        // so a caret parked at the end of a word still selects that word.
        int idx = -1;
        if (offset < source.length() && isIdentifierPart(source.charAt(offset))) {
            idx = offset;
        } else if (offset > 0 && isIdentifierPart(source.charAt(offset - 1))) {
            idx = offset - 1;
        }
        if (idx < 0) {
            return "";
        }
        int start = idx;
        while (start > 0 && isIdentifierPart(source.charAt(start - 1))) {
            start--;
        }
        int end = idx;
        while (end + 1 < source.length() && isIdentifierPart(source.charAt(end + 1))) {
            end++;
        }
        return Character.isJavaIdentifierStart(source.charAt(start))
                ? source.substring(start, end + 1) : "";
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** The 0-based line containing {@code offset}. */
    static int lineAtOffset(String source, int offset) {
        int line = 0;
        int limit = Math.min(Math.max(offset, 0), source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * The 0-based start offset of every whole-word occurrence of {@code word}
     * that sits in <em>code</em>: string/char literals and comments are masked
     * out, matching how an IDE scopes a rename. Works for Java and Kotlin
     * sources alike.
     */
    static List<Integer> identifierOffsets(String source, String word) {
        List<Integer> out = new ArrayList<>();
        if (source == null || word == null || word.isEmpty()) {
            return out;
        }
        boolean[] code = codeMask(source);
        int from = 0;
        while (true) {
            int at = source.indexOf(word, from);
            if (at < 0) {
                return out;
            }
            int end = at + word.length();
            boolean wholeWord = (at == 0 || !isIdentifierPart(source.charAt(at - 1)))
                    && (end == source.length() || !isIdentifierPart(source.charAt(end)));
            if (wholeWord && code[at]) {
                out.add(at);
            }
            from = end;
        }
    }

    /**
     * Per-character mask: true where the character is code, false inside string
     * literals, char literals, line comments or block comments (the delimiters
     * themselves keep the state of the region they open).
     */
    static boolean[] codeMask(String source) {
        boolean[] mask = new boolean[source.length()];
        java.util.Arrays.fill(mask, true);
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int j = i;
                while (j < n && source.charAt(j) != '\n') {
                    mask[j++] = false;
                }
                i = j;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int j = i;
                int k = source.indexOf("*/", j + 2);
                int stop = (k < 0) ? n : k + 2;
                while (j < stop) {
                    mask[j++] = false;
                }
                i = j;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                int j = i;
                mask[j++] = false;
                while (j < n) {
                    char d = source.charAt(j);
                    mask[j] = false;
                    j++;
                    if (d == '\\' && j < n) {
                        mask[j++] = false;
                        continue;
                    }
                    if (d == quote || d == '\n') {
                        break;
                    }
                }
                i = j;
            } else {
                i++;
            }
        }
        return mask;
    }

    // -- actions -----------------------------------------------------------------

    private void goToDefinition() {
        DocumentContext doc = currentDoc;
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        String word = identifierAt(doc.getFullText(), doc.getCaretOffset());
        if (word.isEmpty()) {
            ctx.showMessage("No identifier under the caret");
            return;
        }
        List<StructureSymbol> outline = outlineFor(doc);
        List<Integer> defLines = new ArrayList<>();
        for (StructureSymbol s : outline) {
            String plain = s.name().strip();
            int paren = plain.indexOf('(');
            String simple = (paren >= 0) ? plain.substring(0, paren) : plain;
            if (word.equals(simple)) {
                defLines.add(s.line());
            }
        }
        if (defLines.isEmpty()) {
            ctx.showMessage("No definition of \"" + word + "\" in this file");
            return;
        }
        int caretLine = doc.getCaretLine();
        int target = defLines.size() > 1
                ? defLines.stream().filter(l -> l != caretLine).findFirst().orElse(defLines.get(0))
                : defLines.get(0);
        ctx.navigateTo(doc.getFilePath(), target);
        ctx.showMessage("\"" + word + "\" defined on line " + target);
    }

    private void findOccurrences() {
        DocumentContext doc = currentDoc;
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        String word = identifierAt(doc.getFullText(), doc.getCaretOffset());
        if (word.isEmpty()) {
            ctx.showMessage("No identifier under the caret");
            return;
        }
        List<Integer> lines = new ArrayList<>();
        for (int offset : identifierOffsets(doc.getFullText(), word)) {
            int line = lineAtOffset(doc.getFullText(), offset) + 1;
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            ctx.showMessage("\"" + word + "\" does not occur in this file");
            return;
        }
        int caretLine = doc.getCaretLine();
        Integer next = lines.stream().filter(l -> l > caretLine).findFirst()
                .orElse(lines.get(0));
        ctx.navigateTo(doc.getFilePath(), next);
        ctx.showMessage("\"" + word + "\": " + lines.size() + " occurrence(s) on lines "
                + summarizeLines(lines));
    }

    private void renameSymbol() {
        DocumentContext doc = currentDoc;
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        String word = identifierAt(doc.getFullText(), doc.getCaretOffset());
        if (word.isEmpty()) {
            ctx.showMessage("No identifier under the caret");
            return;
        }
        String replacement = prompt.ask(word);
        if (replacement == null || replacement.isEmpty()
                || replacement.equals(word)
                || !Character.isJavaIdentifierStart(replacement.charAt(0))
                || !replacement.chars().allMatch(c -> isIdentifierPart((char) c))) {
            ctx.showMessage("Rename cancelled (not a valid identifier)");
            return;
        }
        List<Integer> offsets = identifierOffsets(doc.getFullText(), word);
        StringBuilder sb = new StringBuilder(doc.getFullText());
        for (int i = offsets.size() - 1; i >= 0; i--) {
            int start = offsets.get(i);
            sb.replace(start, start + word.length(), replacement);
        }
        doc.setFullText(sb.toString()); // WRITE-gated by the broker; one undo step
        ctx.showMessage("Renamed \"" + word + "\" to \"" + replacement + "\" ("
                + offsets.size() + " occurrence(s))");
    }

    private void organizeImportsAction() {
        DocumentContext doc = currentDoc;
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        String source = doc.getFullText();
        String organized = organizeImports(source);
        if (organized.equals(source)) {
            ctx.showMessage("Imports already organized");
            return;
        }
        doc.setFullText(organized); // WRITE-gated by the broker; one undo step
        ctx.showMessage("Imports organized");
    }

    private List<StructureSymbol> outlineFor(DocumentContext doc) {
        return JavaDiagnosticsTools.detectKind(doc.getFileName(), doc.getFullText())
                == JavaDiagnosticsTools.Lang.KOTLIN
                ? outlineKotlin(doc.getFullText())
                : outlineJava(doc.getFullText(), doc.getFileName());
    }

    // -- organize imports -----------------------------------------------------------

    /**
     * Drops unused single-type imports, removes duplicates and rewrites the
     * block sorted (statics first, then wildcards, then types). A file without
     * imports is returned untouched. Pure and headless so tests can pin its
     * behaviour without any editor.
     */
    static String organizeImports(String source) {
        if (source == null || source.isEmpty()) {
            return (source == null) ? "" : source;
        }
        String[] lines = source.split("\n", -1);
        List<Integer> markers = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (IMPORT_LINE.matcher(lines[i]).matches()) {
                markers.add(i);
            }
        }
        if (markers.isEmpty()) {
            return source; // nothing to organize, nothing to touch
        }
        int first = markers.get(0);
        int last = markers.get(markers.size() - 1);
        Set<String> statics = new TreeSet<>();
        Set<String> wildcards = new TreeSet<>();
        Set<String> singles = new TreeSet<>();
        for (int idx : markers) {
            Matcher m = IMPORT_LINE.matcher(lines[idx]);
            m.matches();
            String dotted = m.group(2);
            if (dotted.endsWith(".*")) {
                wildcards.add(dotted); // a wildcard is never provably unused
            } else if (m.group(1) != null) {
                statics.add(dotted);
            } else {
                String simple = dotted.substring(dotted.lastIndexOf('.') + 1);
                if (usedOutsideRegion(source, first, last, simple)) {
                    singles.add(dotted);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < first; i++) {
            out.append(lines[i]).append('\n');
        }
        for (String s : statics) {
            out.append("import static ").append(s).append(";\n");
        }
        for (String s : wildcards) {
            out.append("import ").append(s).append(";\n");
        }
        for (String s : singles) {
            out.append("import ").append(s).append(";\n");
        }
        // Preserve any non-import lines (a stray comment) inside the old region.
        for (int i = first; i <= last; i++) {
            if (!IMPORT_LINE.matcher(lines[i]).matches() && !lines[i].isBlank()) {
                out.append(lines[i]).append('\n');
            }
        }
        for (int i = last + 1; i < lines.length; i++) {
            out.append(lines[i]);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return keepTrailingNewline(out.toString(), source);
    }

    /** Whether {@code simple} occurs as a whole word outside the {@code [first..last]} import band. */
    private static boolean usedOutsideRegion(String source, int first, int last, String simple) {
        for (int at : identifierOffsets(source, simple)) {
            int line = lineAtOffset(source, at);
            if (line < first || line > last) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the trailing-newline style of the original document. */
    private static String keepTrailingNewline(String rebuilt, String original) {
        if (original.endsWith("\n") && !rebuilt.endsWith("\n")) {
            return rebuilt + "\n";
        }
        while (!original.endsWith("\n") && rebuilt.endsWith("\n")) {
            rebuilt = rebuilt.substring(0, rebuilt.length() - 1);
        }
        return rebuilt;
    }

    private static String summarizeLines(List<Integer> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(i == lines.size() - 1 ? " and " : ", ");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
