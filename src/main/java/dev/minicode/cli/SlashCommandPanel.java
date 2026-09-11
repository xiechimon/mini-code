package dev.minicode.cli;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.jline.widget.Widgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 对齐 {@code pi/pi-tui}（补全下拉面板，docs/wiki/4）：命令面板的 widget 壳——
 * 键绑定接管与状态栏渲染，业务判断全在 {@link PanelModel}（纯状态机，测试缝）。
 * <p>
 * 机制全走 JLine 公开 API（TailTipWidgets 先例）：{@code addWidget} 注册 {@code _panel-*} 变体、
 * {@code aliasWidget} 让内建名指向变体（壳内用 {@code "."} 前缀回调真内建）、渲染走
 * {@link Status}（屏幕底部锚定；不支持的终端 Status 返回 null 或无操作，面板自动不出现 = 降级即现状）。
 * </p>
 * <p>
 * Esc 离散化：emacs 键表里裸 ESC 原本只是转义序列前缀，这里把它绑成面板取消键；
 * {@code AMBIGUOUS_BINDING} 消歧超时从默认 1000ms 调至 100ms——方向键等转义序列整帧到达，
 * 延迟不可感知；仅裸按 Esc 时多等 100ms 关面板（取舍见 docs/adr/0008）。
 * </p>
 */
public final class SlashCommandPanel extends Widgets {

    /** Esc 消歧超时（ms）：裸 Esc 关面板的等待上限，也是方向键转义序列的最长等待。 */
    static final long ESC_AMBIGUOUS_MS = 100L;

    private final PanelModel model;
    private Status status;
    private boolean enabled;
    private Object prevAmbiguous;
    private LineReader.SuggestionType prevSuggestion = LineReader.SuggestionType.NONE;

    public SlashCommandPanel(LineReader reader, SlashDispatcher dispatcher) {
        super(reader);
        this.model = new PanelModel(buildItems(dispatcher));
        addWidget("_panel-self-insert", this::panelSelfInsert);
        addWidget("_panel-delete-char", this::panelDeleteChar);
        addWidget("_panel-backward-delete-char", this::panelBackwardDelete);
        addWidget("_panel-accept-line", this::panelAcceptLine);
        addWidget("_panel-up", this::panelUp);
        addWidget("_panel-down", this::panelDown);
        addWidget("_panel-esc", this::panelEsc);
    }

    /** 候选 = 命令注册表 + 表外的 /exit /quit（与 /help 同源，不出现第三份清单）。包内可见供测试断言同源。 */
    static List<PanelModel.Item> buildItems(SlashDispatcher dispatcher) {
        List<PanelModel.Item> items = new ArrayList<>();
        var table = dispatcher != null ? dispatcher.commands() : SlashCommands.builtins();
        table.forEach((name, e) -> items.add(new PanelModel.Item("/" + name, e.description(), e.takesArg())));
        items.add(new PanelModel.Item("/exit", "退出 REPL", false));
        items.add(new PanelModel.Item("/quit", "退出 REPL", false));
        return items;
    }

    /** 启用面板：别名接管按键 + 绑裸 Esc + 抑制内建建议列表（面板取代它）。 */
    public void enable() {
        if (enabled) {
            return;
        }
        aliasWidget("_panel-self-insert", LineReader.SELF_INSERT);
        aliasWidget("_panel-delete-char", LineReader.DELETE_CHAR);
        aliasWidget("_panel-backward-delete-char", LineReader.BACKWARD_DELETE_CHAR);
        aliasWidget("_panel-accept-line", LineReader.ACCEPT_LINE);
        aliasWidget("_panel-up", LineReader.UP_LINE_OR_SEARCH);
        aliasWidget("_panel-down", LineReader.DOWN_LINE_OR_SEARCH);
        getKeyMap().bind(new Reference("_panel-esc"), "\u001b");
        prevAmbiguous = reader.getVariable(LineReader.AMBIGUOUS_BINDING);
        reader.setVariable(LineReader.AMBIGUOUS_BINDING, ESC_AMBIGUOUS_MS);
        prevSuggestion = reader.getAutosuggestion();
        reader.setAutosuggestion(LineReader.SuggestionType.NONE);
        enabled = true;
    }

    /** 停用：键表别名全部还原、Esc 解绑、恢复消歧超时与建议列表设置。 */
    public void disable() {
        if (!enabled) {
            return;
        }
        aliasWidget("." + LineReader.SELF_INSERT, LineReader.SELF_INSERT);
        aliasWidget("." + LineReader.DELETE_CHAR, LineReader.DELETE_CHAR);
        aliasWidget("." + LineReader.BACKWARD_DELETE_CHAR, LineReader.BACKWARD_DELETE_CHAR);
        aliasWidget("." + LineReader.ACCEPT_LINE, LineReader.ACCEPT_LINE);
        aliasWidget("." + LineReader.UP_LINE_OR_SEARCH, LineReader.UP_LINE_OR_SEARCH);
        aliasWidget("." + LineReader.DOWN_LINE_OR_SEARCH, LineReader.DOWN_LINE_OR_SEARCH);
        getKeyMap().unbind("\u001b");
        // prevAmbiguous 为 null（原本未设）时 setVariable(name, null) 等价还原（getVariable 读回 null）
        reader.setVariable(LineReader.AMBIGUOUS_BINDING, prevAmbiguous);
        reader.setAutosuggestion(prevSuggestion);
        hidePanel();
        enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 供测试与调试读状态。 */
    PanelModel model() {
        return model;
    }

    // ===== widget 体：先调内建（. 前缀），再刷新面板 =====

    private boolean callBuiltin(String name) {
        callWidget(name);
        return true;
    }

    private boolean panelSelfInsert() {
        boolean r = callBuiltin(LineReader.SELF_INSERT);
        refresh();
        return r;
    }

    private boolean panelDeleteChar() {
        boolean r = callBuiltin(LineReader.DELETE_CHAR);
        refresh();
        return r;
    }

    private boolean panelBackwardDelete() {
        boolean r = callBuiltin(LineReader.BACKWARD_DELETE_CHAR);
        refresh();
        return r;
    }

    private boolean panelAcceptLine() {
        Optional<PanelModel.Selection> sel = model.select();
        if (sel.isEmpty()) {
            return callBuiltin(LineReader.ACCEPT_LINE);
        }
        PanelModel.Selection s = sel.get();
        buffer().clear();
        if (s.execute()) {
            // 无参命令：填入并直接提交，REPL 循环接管 dispatch
            buffer().write(s.command());
            closePanel();
            return callBuiltin(LineReader.ACCEPT_LINE);
        }
        // 带参命令：填入 + 空格，面板关闭，留在编辑态等参数
        buffer().write(s.command() + " ");
        closePanel();
        return true;
    }

    private boolean panelUp() {
        if (model.isOpen()) {
            model.move(-1);
            render();
            return true;
        }
        return callBuiltin(LineReader.UP_LINE_OR_SEARCH);
    }

    private boolean panelDown() {
        if (model.isOpen()) {
            model.move(1);
            render();
            return true;
        }
        return callBuiltin(LineReader.DOWN_LINE_OR_SEARCH);
    }

    private boolean panelEsc() {
        // 只关面板，输入行保留；面板未开时为空操作（裸 Esc 的 Emacs 原语义即如此）
        closePanel();
        return true;
    }

    // ===== 状态同步与渲染 =====

    private void refresh() {
        model.onBufferChanged(buffer().toString());
        render();
    }

    private void closePanel() {
        model.close();
        hidePanel();
    }

    private Status status() {
        if (status == null) {
            status = Status.getStatus(reader.getTerminal(), false);
        }
        return status;
    }

    private void hidePanel() {
        Status s = status();
        if (s != null) {
            s.hide();
        }
    }

    private void render() {
        if (!enabled) {
            return;
        }
        if (!model.isOpen()) {
            hidePanel();
            return;
        }
        Status s = status();
        if (s == null) {
            return; // 终端不支持状态栏：静默无面板（降级即现状）
        }
        List<PanelModel.Item> items = model.filtered();
        int nameWidth = items.stream().mapToInt(it -> it.command().length()).max().orElse(0);
        List<AttributedString> lines = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PanelModel.Item it = items.get(i);
            AttributedStringBuilder b = new AttributedStringBuilder();
            if (i == model.selectedIndex()) {
                b.style(AttributedStyle.DEFAULT.inverse());
                b.append("▶ ").append(padRight(it.command(), nameWidth)).append("  ").append(it.description());
            } else {
                b.append("  ").append(padRight(it.command(), nameWidth)).append("  ");
                b.style(AttributedStyle.DEFAULT.faint());
                b.append(it.description());
            }
            lines.add(b.toAttributedString());
        }
        s.update(lines);
    }

    private static String padRight(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
