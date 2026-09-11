package dev.minicode.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 对齐 {@code pi/pi-tui}（补全下拉的行为语义，见 docs/wiki/4）：命令面板的纯状态机——
 * 零 JLine 依赖，本特性的测试缝（延续「纯函数渲染缝」取舍，见 CONTEXT.md 术语表）。
 * <p>
 * 状态 = 候选列表 + 过滤词 + 高亮索引 + 开关。渲染与键绑定的接线在 {@link SlashCommandPanel}（壳），
 * 壳只做「键事件 ↔ 本机转移 ↔ Status 渲染」的翻译，不含业务判断。
 * </p>
 */
public final class PanelModel {

    /** 面板候选：命令名（含 / 前缀）、一行说明、是否需要参数（Enter 分流依据）。 */
    public record Item(String command, String description, boolean takesArg) {
    }

    /** Enter 选中结果：execute=true 无参命令选中即执行；false 带参命令填入等参数。 */
    public record Selection(String command, boolean execute) {
    }

    private final List<Item> items;
    private List<Item> filtered = List.of();
    private int selected = 0;
    private boolean open = false;

    public PanelModel(List<Item> items) {
        this.items = List.copyOf(items);
    }

    /**
     * 输入行内容变化时调用。面板只在「整行是单个以 / 开头的 token」时活跃：
     * 进入参数区（含空白）、删掉 / 、或过滤无匹配，都关闭。
     */
    public void onBufferChanged(String line) {
        if (line == null || !line.startsWith("/") || line.indexOf(' ') >= 0 || line.indexOf('\t') >= 0) {
            close();
            return;
        }
        String filter = line.substring(1).toLowerCase(Locale.ROOT);
        List<Item> next = new ArrayList<>();
        for (Item it : items) {
            if (it.command().substring(1).toLowerCase(Locale.ROOT).startsWith(filter)) {
                next.add(it);
            }
        }
        if (next.isEmpty()) {
            close();
            return;
        }
        if (!open) {
            open = true;
            selected = 0;
        }
        filtered = next;
        if (selected >= filtered.size()) {
            selected = filtered.size() - 1;
        }
    }

    public boolean isOpen() {
        return open;
    }

    public List<Item> filtered() {
        return filtered;
    }

    public int selectedIndex() {
        return selected;
    }

    /** 高亮移动（环绕）。 */
    public void move(int delta) {
        if (!open || filtered.isEmpty()) {
            return;
        }
        selected = Math.floorMod(selected + delta, filtered.size());
    }

    /** 关闭面板（Esc / 删光 / 无匹配 / 选中后）。幂等。 */
    public void close() {
        open = false;
        filtered = List.of();
        selected = 0;
    }

    /** Enter 选中当前高亮；面板关闭或候选为空时返回 empty（调用方走正常提交）。 */
    public Optional<Selection> select() {
        if (!open || filtered.isEmpty()) {
            return Optional.empty();
        }
        Item it = filtered.get(selected);
        return Optional.of(new Selection(it.command(), !it.takesArg()));
    }
}
