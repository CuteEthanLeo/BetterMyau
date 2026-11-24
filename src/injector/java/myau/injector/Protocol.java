package myau.injector;

/** 控制器与 Agent 之间的回环 JSON-lines 协议，必须与 {@code myau.inject.agent.InjectAgent} 一致。 */
public final class Protocol {
    public static final int VERSION = 1;

    private Protocol() {
    }
}
