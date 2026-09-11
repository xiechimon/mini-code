package dev.minicode.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命令面板纯状态机单测（.scratch/command-panel/issues/02）。
 * 测试缝：PanelModel 零 JLine 依赖，直接断言转移结果——延续「纯函数渲染缝」先例。
 */
class PanelModelTest {

    private static List<PanelModel.Item> items() {
        return List.of(
                new PanelModel.Item("/help", "帮助", false),
                new PanelModel.Item("/session", "会话信息", false),
                new PanelModel.Item("/model", "切换模型", true),
                new PanelModel.Item("/new", "新会话", false),
                new PanelModel.Item("/exit", "退出", false));
    }

    @Test
    void opensOnSlashWithAllCandidates() {
        PanelModel m = new PanelModel(items());
        assertFalse(m.isOpen());
        m.onBufferChanged("/");
        assertTrue(m.isOpen());
        assertEquals(5, m.filtered().size());
        assertEquals(0, m.selectedIndex());
    }

    @Test
    void doesNotOpenForPlainText() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("帮我改文件");
        assertFalse(m.isOpen());
        m.onBufferChanged("路径里有/斜杠");
        assertFalse(m.isOpen());
    }

    @Test
    void filtersAsYouType() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("/");
        m.onBufferChanged("/ne");
        assertEquals(List.of("/new"), m.filtered().stream().map(PanelModel.Item::command).toList());
        m.onBufferChanged("/n");
        assertEquals(List.of("/new"), m.filtered().stream().map(PanelModel.Item::command).toList());
    }

    @Test
    void closesWhenSlashDeleted() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("/he");
        assertTrue(m.isOpen());
        m.onBufferChanged("");
        assertFalse(m.isOpen(), "删光 / 后自动关");
    }

    @Test
    void closesOnNoMatch() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("/zzz");
        assertFalse(m.isOpen(), "无匹配自动关");
    }

    @Test
    void closesWhenEnteringArgsRegion() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("/model");
        assertTrue(m.isOpen());
        m.onBufferChanged("/model ");
        assertFalse(m.isOpen(), "进入参数区（空格）即关面板");
    }

    @Test
    void moveWrapsAround() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("/");
        m.move(-1);
        assertEquals(4, m.selectedIndex(), "首项上移环绕到末项");
        m.move(1);
        assertEquals(0, m.selectedIndex());
        m.move(1);
        assertEquals(1, m.selectedIndex());
    }

    @Test
    void selectNoArgExecutesArgFills() {
        PanelModel m = new PanelModel(items());
        m.onBufferChanged("/help");
        Optional<PanelModel.Selection> sel = m.select();
        assertTrue(sel.isPresent());
        assertEquals("/help", sel.get().command());
        assertTrue(sel.get().execute(), "无参命令选中即执行");

        m.onBufferChanged("/model");
        sel = m.select();
        assertTrue(sel.isPresent());
        assertFalse(sel.get().execute(), "带参命令填入等参数");
    }

    @Test
    void selectOnClosedPanelIsEmpty() {
        PanelModel m = new PanelModel(items());
        assertTrue(m.select().isEmpty());
    }

    @Test
    void candidatesComeFromRegistryPlusExit() {
        // 同源锁定：面板候选 = 注册表 + /exit /quit，不出现第三份清单
        List<PanelModel.Item> panelItems = SlashCommandPanel.buildItems(null);
        var names = panelItems.stream().map(PanelModel.Item::command).toList();
        for (String registered : SlashCommands.builtins().keySet()) {
            assertTrue(names.contains("/" + registered), "面板缺注册表命令 /" + registered);
        }
        assertTrue(names.contains("/exit"));
        assertTrue(names.contains("/quit"));
        // takesArg 标记透传
        var model = panelItems.stream().filter(i -> i.command().equals("/model")).findFirst().orElseThrow();
        assertTrue(model.takesArg());
        var help = panelItems.stream().filter(i -> i.command().equals("/help")).findFirst().orElseThrow();
        assertFalse(help.takesArg());
    }
}
