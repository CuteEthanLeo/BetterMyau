package myau.inject.rt;

import java.util.Map;

/**
 * 构建期生成的 {@code <Target>State} 类的运行时后盾：把 Mixin 本会加到目标类上的实例字段，改放到
 * 旁路对象里，按目标实例的身份（==）映射。HotSpot 不允许给已加载的类加字段，这是绕开的办法。
 *
 * <p>每个 State 类持有自己的一张弱键 identity 映射（{@link #newMap()}，Guava {@code MapMaker.weakKeys}），
 * 其静态 {@code get_/set_} 方法经 {@link #of} 取得（必要时新建）该实例对应的 State。新建即运行 State 的
 * 无参构造器，构造器里带着从 Mixin 搬过来的字段初值（如 {@code overrideYaw = NaN}），所以注入前就已存在
 * 的对象（当前玩家、已打开的界面）也拿到正确初值。
 *
 * <p>弱键保证目标实例被回收后 State 不泄漏；identity 语义保证不同实例互不串味。另带一个单元素
 * 最近缓存，服务单例目标（Minecraft、EntityRenderer、RenderManager 等）每帧多次访问的热路径。
 */
public final class FieldStore {
    private FieldStore() {
    }

    /** 新建一张弱键 identity 映射供某个 State 类独占。 */
    public static Map<Object, Object> newMap() {
        return new com.google.common.collect.MapMaker().weakKeys().makeMap();
    }

    /**
     * 取得 owner 对应的 State；没有就用 stateClass 的无参构造器新建一个。
     *
     * @param map        该 State 类独占的映射
     * @param cache      长度为 2 的单元素缓存数组 {lastOwner, lastState}，服务单例热路径；可为 null
     * @param owner      目标实例
     * @param stateClass 要实例化的 State 类
     */
    public static Object of(Map<Object, Object> map, Object[] cache, Object owner, Class<?> stateClass) {
        if (owner == null) {
            throw new NullPointerException("注入字段的宿主实例为 null: " + stateClass.getName());
        }
        if (cache != null && cache[0] == owner) {
            return cache[1];
        }
        Object state = map.get(owner);
        if (state == null) {
            synchronized (map) {
                state = map.get(owner);
                if (state == null) {
                    state = instantiate(stateClass);
                    map.put(owner, state);
                }
            }
        }
        if (cache != null) {
            cache[0] = owner;
            cache[1] = state;
        }
        return state;
    }

    private static Object instantiate(Class<?> stateClass) {
        try {
            return stateClass.newInstance();
        } catch (Throwable e) {
            throw new IllegalStateException("无法实例化注入字段旁路对象: " + stateClass.getName(), e);
        }
    }
}
