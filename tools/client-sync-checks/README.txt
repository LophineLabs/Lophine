Focused block-state synchronization regression checks

Run from the repository root after applying the Minecraft sources. Requires Python 3 and JDK 25.
The prediction and incremental checks use the fastutil JAR already downloaded by the project build.

python tools/client-sync-checks/verify_prediction_acks.py --root . --java-home "$env:JAVA_HOME" --expect fixed --output build/client-sync-checks/prediction
python tools/client-sync-checks/verify_fence_scope.py --root . --java-home "$env:JAVA_HOME" --expect fixed --output build/client-sync-checks/fence
python tools/client-sync-checks/verify_incremental_updates.py --root . --java-home "$env:JAVA_HOME" --expect fixed --output build/client-sync-checks/incremental

Prediction acknowledgements: the actual production acknowledgement/context methods are extracted into a controlled connection harness.
Checks sequence restart after a world change, stale requests, player replacement, partial fences, sequence zero and invalid negatives.
The optional --client-jar argument loads the actual official 26.3 BlockStatePredictionHandler and checks sequence restart.
Official client SHA1: e877b6a07acd633fb3bb475002175cec036e7b87.

Fence state: compiles the actual retain/readyThrough logic and WeakIdentityMap with explicit world/player/connection doubles.
Checks owner changes, stale completions, overlapping operations and exceptional completion; native resync actor scheduling is excluded.

Incremental updates: extracts actual dirty-marking and broadcast-queue methods with controlled readiness and delivery boundaries.
Checks missing neighbours, queued readiness transitions, follow-up updates, light updates, no recipients, unloaded chunks and coalescing.
The initial full-chunk send readiness guard is unchanged.

These standalone tools are not Gradle/JUnit tests and are not automatically invoked by CI.
They establish the tested method contracts, not a real client session or a long-running stability guarantee.
On POSIX shells use "$JAVA_HOME" instead of the PowerShell environment syntax shown above.
