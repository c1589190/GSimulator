package com.gsim.root;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 根节点启动策略 — 判断 data 目录是否严格为空。
 *
 * <p>严格空 data 定义：
 * <ul>
 *   <li>dataRoot 不存在，或</li>
 *   <li>dataRoot 存在但没有任何有效 root/world</li>
 * </ul>
 *
 * <p>有效 root 至少满足：存在 world.md 或 branches/b0000-start.md。
 */
public class RootBootstrapPolicy {

    private static final Logger log = LoggerFactory.getLogger(RootBootstrapPolicy.class);
    public static final String ROOT_BRANCH = "branch.b0000-start";

    /**
     * 检查 dataRoot 是否严格为空（无任何有效 root）。
     */
    public static boolean isStrictlyEmptyDataRoot(Path dataRoot) {
        if (dataRoot == null) return true;
        if (!Files.isDirectory(dataRoot)) return true;
        return !hasAnyRoot(dataRoot);
    }

    /**
     * 是否有任何有效 root/world。
     */
    public static boolean hasAnyRoot(Path dataRoot) {
        if (dataRoot == null) return false;
        Path worldsDir = dataRoot.resolve("worlds");
        if (!Files.isDirectory(worldsDir)) return false;

        try (Stream<Path> stream = Files.list(worldsDir)) {
            return stream.filter(Files::isDirectory).anyMatch(dir -> isValidRoot(dir));
        } catch (Exception e) {
            log.warn("Failed to list worlds directory: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 列出所有有效 root ID。
     */
    public static List<String> listRootIds(Path dataRoot) {
        List<String> ids = new ArrayList<>();
        if (dataRoot == null) return ids;
        Path worldsDir = dataRoot.resolve("worlds");
        if (!Files.isDirectory(worldsDir)) return ids;

        try (Stream<Path> stream = Files.list(worldsDir)) {
            stream.filter(Files::isDirectory)
                    .filter(RootBootstrapPolicy::isValidRoot)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(ids::add);
        } catch (Exception e) {
            log.warn("Failed to list worlds: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * 目录是否是一个有效 root：存在 world.md 或 ROOT_BRANCH 文件。
     */
    public static boolean isValidRoot(Path rootDir) {
        if (!Files.isDirectory(rootDir)) return false;
        return Files.exists(rootDir.resolve("world.md"))
                || Files.exists(rootDir.resolve("branches").resolve(b0000StartFilename()));
    }

    /** 获取 root 目录路径。 */
    public static Path rootDir(Path dataRoot, String rootId) {
        return dataRoot.resolve("worlds").resolve(rootId);
    }

    /** ROOT_BRANCH 对应的文件名。 */
    public static String b0000StartFilename() {
        return ROOT_BRANCH.replace("branch.", "") + ".md";
    }

    /** root-scoped knowledge db 路径。 */
    public static Path knowledgeDbPath(Path dataRoot, String rootId) {
        return rootDir(dataRoot, rootId).resolve("knowledge").resolve("gsim.db");
    }
}
