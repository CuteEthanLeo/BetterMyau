package myau.inject.rt;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建期生成的桥接类 {@code myau.inject.acc.Acc} 的运行时后盾。
 *
 * <p>模组版里 Accessor（{@code @Accessor}/{@code @Invoker}）是 Mixin 往目标类加接口 + 方法实现的；
 * 注入时类已加载，加不了接口。于是构建期把每处 {@code INVOKEINTERFACE IAccessorX.m} 改写成
 * {@code INVOKESTATIC Acc.bridge}，桥接方法转调一个 {@link MethodHandle}。这些句柄在 {@code Acc}
 * 的 {@code <clinit>} 里经本类解析：对私有成员 {@code setAccessible} 后 {@code unreflect}，JIT 可内联到
 * 接近直接字段访问的速度。成员名用 refmap 里的 SRG 名（如 {@code field_71428_T}），生产环境即此名。
 *
 * <p>解析失败直接抛错（{@code <clinit>} 失败会很显眼），因为所有 Accessor 成员在正式 SRG jar 里都
 * 存在，缺失即构建缺陷，会被离线自检提前发现。本类不引用任何 {@code net.minecraft.*} 类型，只用
 * 字符串反射，既与映射解耦，也便于被 LaunchClassLoader 加载。
 */
public final class AccessorRuntime {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private AccessorRuntime() {
    }

    /** 实例或静态字段的读取句柄。实例字段句柄类型为 {@code (Owner)T}，静态为 {@code ()T}。 */
    public static MethodHandle getter(String owner, String field) {
        try {
            Field f = findField(load(owner), field);
            f.setAccessible(true);
            return ignoreReceiverIfStatic(LOOKUP.unreflectGetter(f), f.getModifiers());
        } catch (Throwable e) {
            throw fail("字段读取", owner, field, e);
        }
    }

    /** 字段写入句柄。 */
    public static MethodHandle setter(String owner, String field) {
        try {
            Field f = findField(load(owner), field);
            f.setAccessible(true);
            return ignoreReceiverIfStatic(LOOKUP.unreflectSetter(f), f.getModifiers());
        } catch (Throwable e) {
            throw fail("字段写入", owner, field, e);
        }
    }

    /** 方法调用句柄（{@code @Invoker}）；desc 为方法描述符，用于在重载中精确匹配。 */
    public static MethodHandle invoker(String owner, String method, String desc) {
        try {
            Method m = findMethod(load(owner), method, desc);
            m.setAccessible(true);
            return ignoreReceiverIfStatic(LOOKUP.unreflect(m), m.getModifiers());
        } catch (Throwable e) {
            throw fail("方法调用", owner, method + desc, e);
        }
    }

    /**
     * 桥接方法总是把接收者作为第一个参数传进来（{@code (Object, ...)}）。模组版允许实例 Accessor 指向静态成员
     * （如 EntityLivingBase 的 {@code private static final} sprintingSpeedBoostModifier），此时句柄类型是
     * {@code ()T}，直接调用会抛 WrongMethodTypeException。给静态成员的句柄在最前面补一个被丢弃的 Object 参数。
     */
    private static MethodHandle ignoreReceiverIfStatic(MethodHandle handle, int modifiers) {
        return Modifier.isStatic(modifiers) ? MethodHandles.dropArguments(handle, 0, Object.class) : handle;
    }

    // ───────────────────────── 解析 ─────────────────────────

    static Class<?> load(String owner) {
        String name = owner.replace('/', '.');
        try {
            return Class.forName(name, false, AccessorRuntime.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Accessor 目标类不存在: " + name, e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续沿父类找（混淆名可能声明在父类）。
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name, String desc) throws NoSuchMethodException {
        Class<?>[] params = parseParams(desc, type.getClassLoader());
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // 继续沿父类找。
            }
        }
        // 退一步：只按名字找（desc 解析不出时的兜底）。
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
        }
        throw new NoSuchMethodException(name + desc);
    }

    /** 解析方法描述符的参数部分为 Class[]。 */
    static Class<?>[] parseParams(String desc, ClassLoader loader) {
        List<Class<?>> out = new ArrayList<Class<?>>();
        if (desc == null) return new Class<?>[0];
        int i = desc.indexOf('(');
        int end = desc.indexOf(')');
        if (i < 0 || end < 0) return new Class<?>[0];
        i++;
        while (i < end) {
            int dims = 0;
            while (desc.charAt(i) == '[') { dims++; i++; }
            char c = desc.charAt(i);
            Class<?> base;
            if (c == 'L') {
                int semi = desc.indexOf(';', i);
                String cn = desc.substring(i + 1, semi).replace('/', '.');
                base = classByName(cn, loader);
                i = semi + 1;
            } else {
                base = primitive(c);
                i++;
            }
            out.add(dims == 0 ? base : arrayOf(base, dims));
        }
        return out.toArray(new Class<?>[0]);
    }

    private static Class<?> arrayOf(Class<?> base, int dims) {
        int[] zero = new int[dims];
        return java.lang.reflect.Array.newInstance(base, zero).getClass();
    }

    private static Class<?> classByName(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader != null ? loader : AccessorRuntime.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("描述符里的类不存在: " + name, e);
        }
    }

    private static Class<?> primitive(char c) {
        switch (c) {
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'V': return void.class;
            default: throw new IllegalStateException("未知基本类型: " + c);
        }
    }

    private static IllegalStateException fail(String what, String owner, String member, Throwable e) {
        return new IllegalStateException(
            "无法解析 Accessor " + what + " " + owner.replace('/', '.') + "#" + member, e);
    }
}
