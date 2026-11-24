package myau.injector.ui;

import javax.swing.*;
import java.awt.*;

/** 玻璃卡片容器：圆角、半透明染色、顶部柔光、上亮下暗描边；子组件正常布局在里面。 */
public class GlassPanel extends JPanel {
    private final float radius;

    public GlassPanel(LayoutManager layout, float radius) {
        super(layout);
        this.radius = radius;
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Theme.hq(g0);
        Theme.glass(g, 0, 0, getWidth(), getHeight(), radius, 0);
        g.dispose();
    }
}
