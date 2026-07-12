package com.gsim.output;

import com.gsim.util.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON 输出写入器 — 将结果保存为 JSON 格式。
 */
public class JsonOutputWriter {

    /**
     * 将对象写入 JSON 文件。
     */
    public Path write(Path outputFile, Object data) throws IOException {
        Files.createDirectories(outputFile.getParent());
        String json = JsonUtils.toJson(data);
        Files.writeString(outputFile, json);
        return outputFile;
    }
}
