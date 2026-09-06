package com.github.mmooyyii.malguem;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.webkit.WebView;

// 遥控器上下键的平滑滚动, 替代 WebView 生硬的原生步进/整屏瞬移:
// 短按滚一屏(减速收尾), 长按匀速巡航, 松手顺势滑行减速停 —— 小说页与漫画双栏共用
public class SmoothScroller {

    private final WebView view;
    private ValueAnimator animator;
    private boolean cruising = false;
    private int cruiseDir = 0;

    public SmoothScroller(WebView view) {
        this.view = view;
    }

    @SuppressWarnings("deprecation")
    private int maxScrollY() {
        return Math.max(0, (int) (view.getContentHeight() * view.getScale()) - view.getHeight());
    }

    private int clamp(int y) {
        return Math.max(0, Math.min(maxScrollY(), y));
    }

    public void cancel() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        cruising = false;
    }

    private void animateTo(int target, long duration, TimeInterpolator interpolator) {
        int from = view.getScrollY();
        if (target == from) {
            return;
        }
        animator = ValueAnimator.ofInt(from, target);
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(a -> view.scrollTo(0, (int) a.getAnimatedValue()));
        animator.start();
    }

    // 短按: 平滑滚一屏 (留 10% 重叠), 减速收尾
    public void pageScroll(int dir) {
        cancel();
        animateTo(clamp(view.getScrollY() + dir * (int) (view.getHeight() * 0.9f)),
                320, new DecelerateInterpolator(1.5f));
    }

    // 长按: 匀速巡航 (约每秒 1.5 屏); 按键重复事件反复触发, 已在巡航则忽略
    public void startCruise(int dir) {
        if (cruising && dir == cruiseDir) {
            return;
        }
        cancel();
        int target = dir > 0 ? maxScrollY() : 0;
        int distance = Math.abs(target - view.getScrollY());
        if (distance == 0) {
            return;
        }
        float speed = view.getHeight() * 1.5f; // px/s
        animateTo(target, (long) (distance * 1000L / speed), new LinearInterpolator());
        cruising = true;
        cruiseDir = dir;
    }

    // 松手: 顺势再滑 1/4 屏减速停下, 有惯性手感
    public void stopCruise() {
        if (!cruising) {
            return;
        }
        int dir = cruiseDir;
        cancel();
        animateTo(clamp(view.getScrollY() + dir * (int) (view.getHeight() * 0.25f)),
                260, new DecelerateInterpolator());
    }
}
