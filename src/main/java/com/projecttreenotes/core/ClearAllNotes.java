package com.projecttreenotes.core;

import java.util.Collection;
import java.util.List;

/** Pure transformation used by the IDE action and the package-level tests. */
public final class ClearAllNotes {
    private ClearAllNotes() {
    }

    public static long nonBlankCount(Collection<NoteEntry> entries) {
        return entries.stream().filter(entry -> !entry.comment().isBlank()).count();
    }

    public static List<NoteEntry> apply(Collection<NoteEntry> entries) {
        return entries.stream().map(entry -> entry.withComment("")).toList();
    }
}
