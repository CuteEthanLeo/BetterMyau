package myau.property;

import myau.module.Module;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PropertyManager {
    public LinkedHashMap<Class<?>, ArrayList<Property<?>>> properties = new LinkedHashMap<>();

    // 缓存每个 Module 子类的 Property 字段列表，避免重复反射扫描
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    public static List<Field> getPropertyFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> fields = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (Property.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
            return fields;
        });
    }

    public Property<?> getProperty(Module module, String string) {
        for (Property<?> property : properties.get(module.getClass())) {
            if (property.getName().replace("-", "").equalsIgnoreCase(string.replace("-", ""))) {
                return property;
            }
        }
        return null;
    }
}
