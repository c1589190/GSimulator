package com.gsim.webui;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Map;

/**
 * Thymeleaf 模板渲染器 — 单例封装。
 *
 * <p>模板从 classpath:/webui/templates/ 加载，后缀 .html。
 */
public class TemplateRenderer {

    private static final TemplateEngine ENGINE = createEngine();

    private static TemplateEngine createEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("/webui/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);  // 开发阶段不缓存

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    /**
     * 渲染模板。
     *
     * @param templateName 模板名（不含路径前缀和后缀），如 "index"
     * @param variables    模板变量
     * @return 渲染后的 HTML 字符串
     */
    public static String render(String templateName, Map<String, Object> variables) {
        Context ctx = new Context(Locale.getDefault(), variables);
        return ENGINE.process(templateName, ctx);
    }

    /**
     * 无变量渲染。
     */
    public static String render(String templateName) {
        return render(templateName, Map.of());
    }
}
