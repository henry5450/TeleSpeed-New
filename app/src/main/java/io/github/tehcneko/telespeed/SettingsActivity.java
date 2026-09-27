package io.github.tehcneko.telespeed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.view.View;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, new SettingsFragment()).commit();
        }
    }

    /**
     * 设置界面。
     *
     * <p>旧实现使用 {@code MODE_WORLD_READABLE} 让宿主进程直接读取偏好文件，
     * 该权限自 Android 7.0 起被禁用。现代 libxposed API 改为通过框架服务
     * （{@link XposedService#getRemotePreferences}）读写配置，宿主进程侧的
     * hook 通过 {@code XposedInterface.getRemotePreferences} 读取同一份数据。
     */
    public static class SettingsFragment extends PreferenceFragment {

        private static final String PREFS_GROUP = "conf";
        private static final String KEY_SPEED = "speed";
        private static final String DEFAULT_SPEED = "average";
        private static final long SERVICE_CHECK_DELAY_MS = 2000L;

        private SharedPreferences remotePrefs;
        private ListPreference speedPreference;
        private boolean dialogShown;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.prefs);

            speedPreference = (ListPreference) findPreference(KEY_SPEED);
            speedPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                if (remotePrefs == null) {
                    // 服务尚未就绪，拒绝本次变更以免界面与实际配置不一致
                    return false;
                }
                remotePrefs.edit().putString(KEY_SPEED, (String) newValue).apply();
                return true;
            });

            connectToService();
        }

        @Override
        public void onResume() {
            super.onResume();
            if (remotePrefs != null) {
                syncFromRemote();
            }
        }

        private void connectToService() {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    remotePrefs = service.getRemotePreferences(PREFS_GROUP);
                    runOnUiThreadIfPossible(SettingsFragment.this::syncFromRemote);
                }

                @Override
                public void onServiceDied(XposedService service) {
                    remotePrefs = null;
                }
            });

            new Handler(Looper.getMainLooper()).postDelayed(this::warnIfServiceUnavailable,
                    SERVICE_CHECK_DELAY_MS);
        }

        private void syncFromRemote() {
            if (remotePrefs == null || speedPreference == null) {
                return;
            }
            speedPreference.setValue(remotePrefs.getString(KEY_SPEED, DEFAULT_SPEED));
        }

        private void warnIfServiceUnavailable() {
            if (remotePrefs != null || dialogShown || getActivity() == null) {
                return;
            }
            dialogShown = true;
            new AlertDialog.Builder(getActivity())
                    .setMessage(getString(R.string.not_supported))
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (getActivity() != null) {
                            getActivity().finish();
                        }
                    })
                    .setNegativeButton(R.string.ignore, null)
                    .show();
        }

        private void runOnUiThreadIfPossible(Runnable action) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(action);
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            View list = view.findViewById(android.R.id.list);
            list.setOnApplyWindowInsetsListener((v, insets) -> {
                list.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getStableInsetBottom());
                return insets.consumeSystemWindowInsets();
            });

            super.onViewCreated(view, savedInstanceState);
        }
    }
}
