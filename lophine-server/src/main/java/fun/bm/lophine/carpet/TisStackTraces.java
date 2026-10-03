// SPDX-License-Identifier: LGPL-3.0-or-later
package fun.bm.lophine.carpet;

/**
 * TIS's stack frame expression with the official 26.3 reader's empty mapping result.
 */
public final class TisStackTraces {
    private TisStackTraces() {
    }

    public static StackTraceElement[] rebuild(StackTraceElement[] stack) {
        StackTraceElement[] result = new StackTraceElement[stack.length];
        for (int i = 0; i < stack.length; i++) {
            StackTraceElement frame = stack[i];
            result[i] = new StackTraceElement(frame.getClassName(), frame.getMethodName(), frame.getFileName(), frame.getLineNumber());
        }
        return result;
    }

    public static void rebuildCauseChain(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            cause.setStackTrace(rebuild(cause.getStackTrace()));
        }
    }
}
