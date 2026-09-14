package com.github.mmooyyii.malguem;

import android.app.Activity;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

// 阅读时把系统栏 (顶上那行时间/wifi 与导航栏) 藏掉, 整屏留给书页.
// 主题里的 windowFullscreen 管开屏那一下, 这里管运行期: 弹阅读菜单会让窗口失焦,
// 有些 ROM 趁机把状态栏放回来, 所以两个阅读 Activity 的 onWindowFocusChanged 里要再藏一次.
public class Fullscreen {

    public static void apply(Activity a) {
        var w = a.getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        var c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
