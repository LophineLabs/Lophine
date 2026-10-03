// SPDX-License-Identifier: MIT
// Adapted from Carpet Org Addition c2142c213269f85fb1851263bf60f147849224a1; copyright (c) 2024 fcsailboat.
package fun.bm.lophine.carpet;

public final class OrgActionInfiniteLoopException extends IllegalStateException {
    public OrgActionInfiniteLoopException() {
        super("Maximum loop count exceeded, possible infinite loop detected");
    }
}
