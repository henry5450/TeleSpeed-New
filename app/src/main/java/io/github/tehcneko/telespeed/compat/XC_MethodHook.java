package io.github.tehcneko.telespeed.compat;

import java.lang.reflect.Member;

/**
 * 旧版 Xposed API 的 {@code XC_MethodHook} 等价物。
 *
 * <p>现代 API 使用 {@link io.github.libxposed.api.XposedInterface.Hooker} 链式拦截，
 * 这里把它重新包装成 before / after 两个回调，使既有 Hook 代码无需改写。
 *
 * <p>语义与旧版保持一致：在 {@code beforeHookedMethod} 中调用 {@link MethodHookParam#setResult}
 * 或 {@link MethodHookParam#setThrowable} 会跳过原方法；在 {@code afterHookedMethod} 中调用
 * 则覆盖原方法的返回值。
 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;

    private final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 传递给 before / after 回调的参数包，字段语义与旧版 API 保持一致。 */
    public static final class MethodHookParam {

        /** 被调用方法所属的实例；静态方法为 null。 */
        public Object thisObject;

        /** 调用参数，可原地修改（修改后 as 新参数调用原方法）。 */
        public Object[] args;

        private Member method;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        void init(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Member getMethod() {
            return method;
        }

        public Object getResult() {
            return result;
        }

        /** 设置返回值。在 before 阶段调用会跳过原方法。 */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        /** 设置待抛出异常。在 before 阶段调用会跳过原方法。 */
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public int getArgsCount() {
            return args == null ? 0 : args.length;
        }

        boolean isReturnEarly() {
            return returnEarly;
        }

        void beginAfterPhase() {
            this.returnEarly = false;
        }

        void applyInvocationResult(Object value) {
            this.result = value;
            this.throwable = null;
        }

        void applyInvocationThrowable(Throwable t) {
            this.throwable = t;
        }
    }
}
