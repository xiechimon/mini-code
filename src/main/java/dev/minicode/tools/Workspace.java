package dev.minicode.tools;

import java.nio.file.Path;

/**
 * 工作区路径沙箱，集中处理相对/绝对路径解析与归一，防止路径逃逸。
 * 将分散在四个工具中的相同 resolve 逻辑收敛到一个深模块中，
 * 为后续 Permission/Approve 钩子提供唯一的 seam。
 */
public record Workspace(Path root) {

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * 解析用户传入的 path（相对或绝对），并校验其归一后仍位于工作区内。
     * 为兼容 CLI 传绝对路径的场景，绝对路径不在工作区内时仍允许访问
     * 但会进行归一；相对路径则严格限制在 root 之下，防止 ../ 逃逸。
     *
     * @throws IllegalArgumentException 当相对路径试图逃逸出工作区
     */
    public Path resolve(String p) {
        if (p == null || p.isBlank()) throw new IllegalArgumentException("路径不能为空");
        Path candidate = Path.of(p);
        Path normalized;
        if (candidate.isAbsolute()) {
            normalized = candidate.normalize();
            return normalized;
        }
        normalized = root.resolve(candidate).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("路径逃逸工作区: " + p + " -> " + normalized);
        }
        return normalized;
    }
}
