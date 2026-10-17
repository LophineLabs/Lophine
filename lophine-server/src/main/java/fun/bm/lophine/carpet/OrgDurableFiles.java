// SPDX-License-Identifier: MIT
package fun.bm.lophine.carpet;

import java.io.IOException;
import java.nio.file.*;

final class OrgDurableFiles {
    private OrgDurableFiles() {
    }

    static void replace(Path staging, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException denied) {
                // Windows scanners can briefly deny rename after a staging file closes.
                // Retry only the replacement, never the asset mutation or its durable write.
                if (attempt == 7) throw denied;
                Thread.yield();
            }
        }
    }
}
