package io.github.tehcneko.telespeed.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 旧版 Xposed API 中 {@code XposedHelpers} 的最小可用实现。
 *
 * <p>现代 libxposed API 不再提供该工具类（官方 helper 库未发布到 Maven Central），
 * 因此这里基于 {@link java.lang.reflect} 重新实现本项目所需的子集，
 * 使既有 Hook 代码可以逐字保留。
 */
public final class XposedHelpers {

    private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPERS = new HashMap<>(16);
    private static final Map<String, Class<?>> PRIMITIVE_BY_NAME = new HashMap<>(16);

    static {
        register(void.class, Void.class);
        register(boolean.class, Boolean.class);
        register(byte.class, Byte.class);
        register(char.class, Character.class);
        register(short.class, Short.class);
        register(int.class, Integer.class);
        register(long.class, Long.class);
        register(float.class, Float.class);
        register(double.class, Double.class);
    }

    private XposedHelpers() {
    }

    private static void register(Class<?> primitive, Class<?> wrapper) {
        PRIMITIVE_WRAPPERS.put(primitive, wrapper);
        PRIMITIVE_BY_NAME.put(primitive.getName(), primitive);
    }

    // ------------------------------------------------------------------
    // 类查找
    // ------------------------------------------------------------------

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (classLoader == null) {
            classLoader = XposedHelpers.class.getClassLoader();
        }
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className, e);
        }
    }

    // ------------------------------------------------------------------
    // Hook 注册
    // ------------------------------------------------------------------

    /** 按类名查找并 Hook 方法（旧版 API 的常用重载）。 */
    public static void findAndHookMethod(String className, ClassLoader classLoader,
                                         String methodName, Object... parameterTypesAndCallback) {
        Class<?> clazz = findClass(className, classLoader);
        hookMethod(clazz, classLoader, methodName, parameterTypesAndCallback);
    }

    /** 直接以 {@link Class} 指定目标类。 */
    public static void findAndHookMethod(Class<?> clazz, String methodName,
                                         Object... parameterTypesAndCallback) {
        hookMethod(clazz, clazz.getClassLoader(), methodName, parameterTypesAndCallback);
    }

    private static void hookMethod(Class<?> clazz, ClassLoader classLoader, String methodName,
                                   Object[] parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("最后一个参数必须是 XC_MethodHook");
        }
        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];

        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = resolveType(parameterTypesAndCallback[i], classLoader);
        }

        Method method = findMethodExact(clazz, methodName, parameterTypes);
        hookMethod(method, callback);
    }

    /** 把 {@link Method} 交给框架，并以 before / after 回调的形式分发。 */
    public static void hookMethod(Method method, XC_MethodHook callback) {
        XposedBridge.requireInterface()
                .hook(method)
                .setPriority(callback.getPriority())
                .intercept(chain -> {
                    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                    param.init(method, chain.getThisObject(), chain.getArgs().toArray());

                    try {
                        callback.beforeHookedMethod(param);
                    } catch (Throwable t) {
                        XposedBridge.log("before 回调异常: " + method);
                        XposedBridge.log(t);
                    }
                    if (param.isReturnEarly()) {
                        if (param.hasThrowable()) {
                            throw param.getThrowable();
                        }
                        return param.getResult();
                    }

                    Object result = null;
                    Throwable failure = null;
                    try {
                        result = chain.proceed(param.args);
                    } catch (Throwable t) {
                        failure = t;
                    }

                    param.beginAfterPhase();
                    if (failure != null) {
                        param.applyInvocationThrowable(failure);
                    } else {
                        param.applyInvocationResult(result);
                    }
                    try {
                        callback.afterHookedMethod(param);
                    } catch (Throwable t) {
                        XposedBridge.log("after 回调异常: " + method);
                        XposedBridge.log(t);
                    }

                    if (param.hasThrowable()) {
                        throw param.getThrowable();
                    }
                    return param.getResult();
                });
    }

    // ------------------------------------------------------------------
    // 字段读写
    // ------------------------------------------------------------------

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        try {
            findField(obj.getClass(), fieldName).setLong(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getBoolean(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        try {
            findField(obj.getClass(), fieldName).setBoolean(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 方法调用
    // ------------------------------------------------------------------

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method method = findMethodByArgs(clazz, methodName, args);
        try {
            return method.invoke(null, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        Method method = findMethodByArgs(obj.getClass(), methodName, args);
        try {
            return method.invoke(obj, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError(e.getCause());
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != args.length) {
                continue;
            }
            if (matchScore(constructor.getParameterTypes(), args) < 0) {
                continue;
            }
            constructor.setAccessible(true);
            try {
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new InvocationTargetError(e.getCause() == null ? e : e.getCause());
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "(...): 找不到匹配的构造函数");
    }

    // ------------------------------------------------------------------
    // 内部查找逻辑
    // ------------------------------------------------------------------

    private static Class<?> resolveType(Object type, ClassLoader classLoader) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof String) {
            String name = (String) type;
            Class<?> primitive = PRIMITIVE_BY_NAME.get(name);
            return primitive != null ? primitive : findClass(name, classLoader);
        }
        throw new IllegalArgumentException("无法解析参数类型: " + type);
    }

    private static Method findMethodExact(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续向父类查找
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "." + methodName + "(...)");
    }

    private static Method findMethodByArgs(Class<?> clazz, String methodName, Object[] args) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            Method best = null;
            int bestScore = -1;
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)
                        || method.getParameterCount() != args.length) {
                    continue;
                }
                // 静态方法只能通过 callStaticMethod 调用，这里按目标实例的存在与否筛选
                int score = matchScore(method.getParameterTypes(), args);
                if (score > bestScore) {
                    bestScore = score;
                    best = method;
                }
            }
            if (best != null) {
                best.setAccessible(true);
                return best;
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "." + methodName + "(...)");
    }

    /**
     * 计算参数匹配得分：完全相等 4 分，可赋值 2 分，不匹配 -1 分。
     * 得分高者优先，用于在重载方法中挑选最贴近的那个。
     */
    private static int matchScore(Class<?>[] parameterTypes, Object[] args) {
        if (parameterTypes.length != args.length) {
            return -1;
        }
        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            Class<?> expected = wrap(parameterTypes[i]);
            if (arg == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return -1;
                }
                score += 1;
            } else if (expected == arg.getClass()) {
                score += 4;
            } else if (expected.isAssignableFrom(arg.getClass())) {
                score += 2;
            } else {
                return -1;
            }
        }
        return score;
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        Class<?> wrapper = PRIMITIVE_WRAPPERS.get(type);
        return wrapper == null ? type : wrapper;
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "." + fieldName);
    }

    // ------------------------------------------------------------------
    // 与旧版 API 同名的异常类型
    // ------------------------------------------------------------------

    public static class ClassNotFoundError extends Error {
        public ClassNotFoundError(String className, Throwable cause) {
            super("找不到类: " + className, cause);
        }
    }

    public static class InvocationTargetError extends Error {
        public InvocationTargetError(Throwable cause) {
            super(cause);
        }
    }
}
