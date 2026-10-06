package fun.bm.lophine.carpet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TisStackTraceCycleTest {
    private static final class Cause extends RuntimeException {
        Throwable next;
        int reads;

        @Override public synchronized Throwable getCause() {
            if (++reads > 1) throw new AssertionError("Cyclic cause was visited again");
            return next;
        }
    }

    @Test void cyclicNativeErrorsAreRewrittenOnceInsteadOfLoopingOnTheReportingThread() {
        var first = new Cause();
        var second = new Cause();
        first.next = second;
        second.next = first;
        var frame = new StackTraceElement("sample.Native", "run", "Native.java", 7);
        first.setStackTrace(new StackTraceElement[]{frame});
        second.setStackTrace(new StackTraceElement[]{frame});
        TisStackTraces.rebuildCauseChain(first);
        assertEquals(1, first.reads);
        assertEquals(1, second.reads);
        assertArrayEquals(new StackTraceElement[]{frame}, first.getStackTrace());
        assertArrayEquals(new StackTraceElement[]{frame}, second.getStackTrace());
    }
}
