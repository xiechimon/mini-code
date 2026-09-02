package dev.minicode.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteEditBashTest {

    @Test
    void writeAndRead(@TempDir Path tmp) throws Exception {
        WriteTool w = new WriteTool(tmp);
        ToolResult r = w.execute("1", Map.of("path", "out.txt", "content", "hello java"));
        assertFalse(r.isError());
        assertEquals("hello java", Files.readString(tmp.resolve("out.txt")));
    }

    @Test
    void editReplacesUnique(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "foo bar foo");
        // not unique -> error
        EditTool e = new EditTool(tmp);
        ToolResult r1 = e.execute("1", Map.of("path", "a.txt", "oldText", "foo", "newText", "qux"));
        assertTrue(r1.isError());
        Files.writeString(f, "hello world");
        ToolResult r2 = e.execute("1", Map.of("path", "a.txt", "oldText", "world", "newText", "java"));
        assertFalse(r2.isError());
        assertEquals("hello java", Files.readString(f));
    }

    @Test
    void bashExecutes(@TempDir Path tmp) throws Exception {
        BashTool b = new BashTool(tmp);
        ToolResult r = b.execute("1", Map.of("command", "echo hi"));
        assertFalse(r.isError());
        assertTrue(r.content().contains("hi"));
    }

    @Test
    void bashReportsErrorOnFailure(@TempDir Path tmp) throws Exception {
        BashTool b = new BashTool(tmp);
        ToolResult r = b.execute("1", Map.of("command", "exit 1"));
        assertTrue(r.isError());
    }
}
