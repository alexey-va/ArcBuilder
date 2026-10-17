# Real Paper tests

Use Java 25 and build ARC from commit
`a64c7345370d8913cfff0911029e215ffd209c47` (version `1.4.408`). Pass that
production JAR explicitly:

```bash
./gradlew prepareE2eRuntimeArtifacts -Pe2eArcJar=/absolute/path/ARC-1.4.408.jar
./gradlew plugwrightTest -Pe2eArcJar=/absolute/path/ARC-1.4.408.jar
```

The preparation task resolves the non-transitive `ru.ruscrafting:ArcCore:0.1.9`
artifact from the configured Maven repository. It checks both plugin versions,
the pinned ArcCore SHA-256 and shared schematic API ownership, then stages
`build/e2e-runtime/ARC.jar` and `ArcCore.jar`. An explicit
`-Pe2eArcCoreJar=/absolute/path/ArcCore-0.1.9.jar` uses the same validation for a
local candidate. No plugin JAR is resolved during ordinary build configuration.
Preparation does not launch Paper, Node, clients, or external services.

Plugwright installs both staged plugins separately, downloads its pinned Node
22.14.0 runner, Paper 1.21.11, PacketEvents 2.12.1, LuckPerms 5.5.17 and
WorldEdit 7.4.2. ArcCore owns SQL/Redis/metrics/schematic service contracts and the
schematic provider; ARC and ArcBuilder consume that shared classloader identity.
GitHub Actions builds the pinned ARC checkout, prepares the same two artifacts,
then runs these tests independently of JVM and MySQL tests. It checks that npm
did not change the lockfile and retains server logs plus the artifact receipt.

The disposable server binds to localhost:25565 and accepts offline test bots.
Its generated world and plugin data are reset for each run. Do not point this
fixture at a running server. CoreProtect auditing is disabled only in this
fixture; WorldEdit is present so Paper can register the real builder listeners.
The disabled catalogue entry is metadata only; its dummy schematic is never
loaded or offered as a building book. Provider registration is available because
WorldEdit loads before ArcCore; this fixture does not preload the library.

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
