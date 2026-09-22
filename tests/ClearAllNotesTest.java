import com.projecttreenotes.core.ClearAllNotes;
import com.projecttreenotes.core.NoteEntry;
import com.projecttreenotes.core.NoteType;

import java.util.List;

public final class ClearAllNotesTest {
    public static void main(String[] args) {
        List<NoteEntry> original = List.of(
                new NoteEntry(NoteType.PROJECT, ".", "项目说明"),
                new NoteEntry(NoteType.DIRECTORY, "src", "源码目录"),
                new NoteEntry(NoteType.FILE, "src/main.py", "入口文件"),
                new NoteEntry(NoteType.MISSING, "old.txt", "")
        );

        require(ClearAllNotes.nonBlankCount(original) == 3, "应统计三条非空说明");
        List<NoteEntry> cleared = ClearAllNotes.apply(original);
        require(cleared.size() == original.size(), "清除后不得减少路径行");
        for (int index = 0; index < original.size(); index++) {
            require(cleared.get(index).type() == original.get(index).type(), "条目类型必须保留");
            require(cleared.get(index).path().equals(original.get(index).path()), "条目路径必须保留");
            require(cleared.get(index).comment().isEmpty(), "每条说明都必须清空");
        }
        require(ClearAllNotes.apply(List.of()).isEmpty(), "空表应保持为空");
        require(ClearAllNotes.nonBlankCount(cleared) == 0, "重复清除应保持零条说明");
        System.out.println("ClearAllNotesTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
