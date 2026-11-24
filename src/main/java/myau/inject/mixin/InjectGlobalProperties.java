package myau.inject.mixin;

import org.spongepowered.asm.service.IGlobalPropertyService;

import java.util.HashMap;
import java.util.Map;

/**
 * 内存版全局属性服务。我们这套 Mixin 引擎经 Shadow 重定位成 {@code myau.inject.shadow.*}，它的
 * ServiceLoader 只会找到本服务，和游戏里已有的（未重定位的）Mixin 各用各的黑板，互不影响——不去碰
 * {@code Launch.blackboard}。写法对应被重定位后的 {@code IGlobalPropertyService}。
 */
public final class InjectGlobalProperties implements IGlobalPropertyService {
    private final Map<String, Object> properties = new HashMap<String, Object>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key) {
        return (T) properties.get(key);
    }

    @Override
    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(String key, T defaultValue) {
        Object value = properties.get(key);
        return value != null ? (T) value : defaultValue;
    }

    @Override
    public String getPropertyString(String key, String defaultValue) {
        Object value = properties.get(key);
        return value != null ? value.toString() : defaultValue;
    }
}
