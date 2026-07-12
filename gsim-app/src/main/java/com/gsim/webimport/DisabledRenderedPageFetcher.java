package com.gsim.webimport;

/**
 * DisabledRenderedPageFetcher — 默认实现，JS 渲染未启用。
 */
public class DisabledRenderedPageFetcher implements RenderedPageFetcher {

    private static final String WARNING = "JS rendering is not enabled. Content may be incomplete for JS-heavy pages.";

    @Override
    public FetchResult fetchRendered(String url) {
        return FetchResult.warning("", WARNING);
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String name() {
        return "disabled";
    }
}
