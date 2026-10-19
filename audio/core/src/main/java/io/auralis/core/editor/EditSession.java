package io.auralis.core.editor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Bounded project-state history; originals are never touched by undo or redo. */
public final class EditSession {
    private final Deque<EditProject> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private EditProject project;
    public EditSession(EditProject project) { this.project = Objects.requireNonNull(project); }
    public EditProject project() { return project; }
    public void apply(UnaryOperator<EditProject> operation) { setProject(operation.apply(project)); }
    public void setProject(EditProject updated) {
        Objects.requireNonNull(updated); if (updated.equals(project)) return;
        undo.push(project); if (undo.size() > 100) undo.removeLast(); redo.clear(); project = updated;
    }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public boolean undo() { if (undo.isEmpty()) return false; redo.push(project); project = undo.pop(); return true; }
    public boolean redo() { if (redo.isEmpty()) return false; undo.push(project); project = redo.pop(); return true; }
}
