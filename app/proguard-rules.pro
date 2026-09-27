# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Xposed 模块入口类：libxposed API 102 原生支持 R8，
# 入口类允许混淆但必须保留无参构造函数。
-keep,allowoptimization,public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 模块元数据文件内容需要在构建期被 R8 适配，保证 java_init.list 中的类名与混淆结果一致。
-adaptresourcefilecontents META-INF/xposed/java_init.list

# libxposed 框架类不能被打包/混淆警告干扰
-dontwarn io.github.libxposed.annotation.**
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }

# io.github.libxposed:service 的公开 API 上带有 androidx.annotation 注解，
# 二者仅在编译期有意义（RetentionPolicy.CLASS），运行期无需存在。
-dontwarn androidx.annotation.**

# 反射访问宿主类字段/方法，保留必要的属性
-keepattributes SourceFile,LineNumberTable
