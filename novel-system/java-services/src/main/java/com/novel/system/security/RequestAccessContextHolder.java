package com.novel.system.security;

public final class RequestAccessContextHolder {

    private static final ThreadLocal<RequestAccessContext> CURRENT = ThreadLocal.withInitial(RequestAccessContext::local);

    private RequestAccessContextHolder() {
    }

    public static RequestAccessContext current() {
        return CURRENT.get();
    }

    public static void set(RequestAccessContext context) {
        CURRENT.set(context == null ? RequestAccessContext.local() : context);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
