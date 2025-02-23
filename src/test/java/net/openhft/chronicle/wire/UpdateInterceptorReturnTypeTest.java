package net.openhft.chronicle.wire;

import org.junit.Test;

import static net.openhft.chronicle.bytes.Bytes.elasticByteBuffer;
import static net.openhft.chronicle.wire.WireType.BINARY;

public class UpdateInterceptorReturnTypeTest {

    @Test
    public void testUpdateInterceptorNoReturnType() {

        BINARY.apply(elasticByteBuffer())
                .methodWriterBuilder(NoReturnType.class)
                .updateInterceptor((methodName, t) -> true)
                .build()
                .x("hello world");
    }

    @Test
    public void testUpdateInterceptorWithIntReturnType() {
        BINARY.apply(elasticByteBuffer())
                .methodWriterBuilder(WithIntReturnType.class)
                .updateInterceptor((methodName, t) -> true)
                .build()
                .x("hello world");
    }

    @Test
    public void testUpdateInterceptorWithObjectReturnType() {
        BINARY.apply(elasticByteBuffer())
                .methodWriterBuilder(WithObjectReturnType.class)
                .updateInterceptor((methodName, t) -> true)
                .build()
                .x("hello world");
    }

    @Test
    public void testUpdateInterceptorWithLadderByQtyListener() {
        BINARY.apply(elasticByteBuffer())
                .methodWriterBuilder(LadderByQtyListener.class)
                .updateInterceptor((methodName, t) -> true)
                .build()
                .ladderByQty("a ladder");
    }

    public interface LadderByQtyListener {
        void ladderByQty(String ladder);

        default void lbq(String name, String ladder) {
            ladderByQty(ladder);
        }

        default boolean ignoreMethodBasedOnFirstArg(String methodName, String ladderDefinitionName) {
            return false;
        }
    }

    interface NoReturnType {
        void x(String x);
    }

    interface WithIntReturnType {
        int x(String x);
    }

    interface WithObjectReturnType {
        Object x(String x);
    }

    interface WithObjectVoidReturnType {
        Void x(String x);
    }
}