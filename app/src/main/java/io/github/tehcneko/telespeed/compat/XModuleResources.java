package io.github.tehcneko.telespeed.compat;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import java.lang.reflect.Method;

/**
 * 旧版 Xposed API 的 {@code XModuleResources} 等价物。
 *
 * <p>现代 libxposed API 移除了资源 Hook 支持，但模块仍然需要读取自己的字符串资源
 * （加速提示文案等）。这里通过把模块 APK 加入 {@link AssetManager} 的方式，
 * 在宿主进程内重建一份模块 {@link Resources}。
 */
public class XModuleResources {

    private final Resources resources;

    private XModuleResources(Resources resources) {
        this.resources = resources;
    }

    /**
     * @param modulePath 模块 APK 的路径
     * @param base       用于继承显示参数的宿主 Resources，可为 null
     */
    public static XModuleResources createInstance(String modulePath, Resources base) {
        if (modulePath == null) {
            throw new IllegalArgumentException("modulePath 不能为空");
        }
        try {
            AssetManager assetManager = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object result = addAssetPath.invoke(assetManager, modulePath);
            if (result instanceof Integer && (Integer) result == 0) {
                throw new IllegalStateException("addAssetPath 失败: " + modulePath);
            }

            Resources source = base != null ? base : Resources.getSystem();
            DisplayMetrics metrics = source.getDisplayMetrics();
            Configuration configuration = source.getConfiguration();

            @SuppressWarnings("deprecation")
            Resources resources = new Resources(assetManager, metrics, configuration);
            return new XModuleResources(resources);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法加载模块资源: " + modulePath, e);
        }
    }

    public String getString(int id) {
        return resources.getString(id);
    }

    public String getString(int id, Object... formatArgs) {
        return resources.getString(id, formatArgs);
    }

    public CharSequence getText(int id) {
        return resources.getText(id);
    }

    public Resources getResources() {
        return resources;
    }
}
