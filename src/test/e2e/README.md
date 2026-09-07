# Real Paper tests

Run `./gradlew plugwrightTest` with Java 25. Plugwright downloads its pinned
Node 22.14.0 runner, Paper 1.21.11, LuckPerms 5.5.17 and WorldEdit 7.4.2.
GitHub Actions runs the same command independently of JVM and MySQL tests,
checks that npm did not change the lockfile, and retains server logs on failure.

The disposable server binds to localhost:25565 and accepts offline test bots.
Its generated world and plugin data are reset for each run. Do not point this
fixture at a running server. CoreProtect auditing is disabled only in this
fixture; WorldEdit is present so Paper can register the real builder listeners.
The disabled catalog entry contains only a digest fixture to satisfy catalog
startup validation; its contents are never loaded or offered as a building book.

The tests cover denied command access and a survival player selecting two
blocks, previewing a replacement without changing world blocks, confirming the build,
then undoing it. Server block queries, a second client's block updates and
inventory counts verify the result, including returned materials. Cancellation
rejects a stale confirmation and creates no undo record. Insufficient materials
leave both blocks and inventory untouched; supplying the missing item allows
one commit, and a repeated confirmation cannot duplicate returned materials.

Paid material purchases, protection integrations, build-book placement and
database recovery remain outside this suite. Existing JVM and MySQL tests
remain in place.
