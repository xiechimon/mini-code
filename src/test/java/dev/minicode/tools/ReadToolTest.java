package dev.minicode.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {

    @Test
    void readsFile(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "hello\nworld");
        ReadTool tool = new ReadTool(tmp);
        ToolResult r = tool.execute("1", Map.of("path", "a.txt"));
        assertFalse(r.isError());
        assertTrue(r.content().contains("hello"));
        assertTrue(r.content().contains("world"));
    }

    @Test
    void offsetAndLimit(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("b.txt");
        Files.writeString(f, "1\n2\n3\n4\n5");
        ReadTool tool = new ReadTool(tmp);
        ToolResult r = tool.execute("1", Map.of("path", "b.txt", "offset", 2, "limit", 2));
        assertFalse(r.isError());
        assertTrue(r.content().contains("2\n3"));
        assertFalse(r.content().contains("4\n"));
    }

    @Test
    void missingFile(@TempDir Path tmp) throws Exception {
        ReadTool tool = new ReadTool(tmp);
        ToolResult r = tool.execute("1", Map.of("path", "nope.txt"));
        assertTrue(r.isError());
    }

    @Test
    void truncatesAtLimits(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++) sb.append("line ").append(i).append("\n");
        Files.writeString(f, sb.toString());
        ReadTool tool = new ReadTool(tmp);
        ToolResult r = tool.execute("1", Map.of("path", "big.txt"));
        assertFalse(r.isError());
        assertTrue(r.content().contains("截断") || r.content().contains("truncated"));
    }
}
