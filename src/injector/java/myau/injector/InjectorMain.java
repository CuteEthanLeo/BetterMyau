package myau.injector;

import myau.injector.ui.InjectorWindow;
import myau.injector.ui.Theme;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * BetterMyau 热注入控制器入口。界面在 {@link InjectorWindow}（自绘玻璃风格，和游戏内 UI 一套视觉），
 * 注入逻辑在 {@link InjectionSession}，进程识别在 {@link TargetScanner}。
 */
public final class InjectorMain {
    private InjectorMain() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            // 提示框换成深色玻璃风格（默认的是系统浅黄色）。
            UIManager.put("ToolTip.background", new java.awt.Color(0x1c, 0x1f, 0x27));
            UIManager.put("ToolTip.foreground", Theme.TEXT);
            UIManager.put("ToolTip.font", Theme.ui(12f, false));
            UIManager.put("ToolTip.border", new CompoundBorder(
                new LineBorder(new java.awt.Color(0x33, 0x39, 0x45)), new EmptyBorder(5, 9, 5, 9)));
            new InjectorWindow().start();
        });
    }
}
