import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("io.github.drownek.plugwright") version "2.0.4"
    jacoco
}

group = "ru.ruscrafting"
version = "1.0.66"
description = "Survival-friendly builder tools for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://jitpack.io")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.30")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.30")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.30")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.30")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.30")
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.30")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.30")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.jeff-media:custom-block-data:2.2.4")
    implementation("de.tr7zw:item-nbt-api:2.15.7")

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    compileOnly("com.github.angeschossen:LandsAPI:6.26.18")
    compileOnly("com.github.Slimefun:Slimefun4:4.10")
    compileOnly("com.github.LoneDev6:api-itemsadder:3.6.1")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("ru.ruscrafting.thirdparty:economyshopgui-premium:6.3.0")
    compileOnly("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    compileOnly(files("libs/zauctionhouse-api-4.0.1.2.jar"))

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.kotest:kotest-property:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.30")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.30")
    testImplementation("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    testImplementation("com.github.Slimefun:Slimefun4:4.10")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.30")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    processResources {
        inputs.property("pluginVersion", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        systemProperty("arcbuilder.projectDir", projectDir.absolutePath)
        providers.gradleProperty("ruscraftingOpsRoot")
            .orElse(providers.environmentVariable("RUSCRAFTING_OPS_ROOT"))
            .orNull
            ?.let { systemProperty("ruscrafting.opsRoot", it) }
    }
    register<Test>("integrationTest") {
        description = "Runs disposable MySQL integration tests."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    register<JavaExec>("rebaseSchematicOrigin") {
        description = "Moves schematic placement in world space by changing its Sponge offset."
        group = "builder maintenance"
        dependsOn(testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass.set("ru.arc.autobuild.SchematicOriginTool")
        val input = providers.gradleProperty("schematicInput")
        val output = providers.gradleProperty("schematicOutput")
        val shiftY = providers.gradleProperty("schematicShiftY")
        doFirst {
            args(input.get(), output.get(), shiftY.get())
        }
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
        exclude("org/bukkit/**")
        exclude("io/papermc/**")
        exclude("com/sk89q/**")
        exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
        exclude("ru/arc/paper/api/**")
        exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
        exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
        exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
        exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
        exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
        relocate("com.github.stefvanschie.inventoryframework", "ru.ruscrafting.builder.libs.inventoryframework")
        relocate("com.jeff_media.customblockdata", "ru.ruscrafting.builder.libs.customblockdata")
        relocate("de.tr7zw.changeme.nbtapi", "ru.ruscrafting.builder.libs.nbtapi")
    }
    check { dependsOn(shadowJar) }
}

// E2E uses the same separate service provider and API owner as production.
val e2eArcVersion = "1.4.403"
val e2eArcCoreVersion = "0.1.6"
val e2eArcCoreSha256 = "d5fb71556f5bf9d87c4ab5b7a343ff8562187d53aa319f1d02f58f4987af974f"
val e2eArcJar = providers.gradleProperty("e2eArcJar").map { project.file(it) }
val e2eArcCoreArtifact = configurations.detachedConfiguration(
    dependencies.create("ru.ruscrafting:ArcCore:$e2eArcCoreVersion"),
).apply { isTransitive = false }
val e2eArcCoreJar = providers.gradleProperty("e2eArcCoreJar").map { project.file(it) }
    .orElse(providers.provider { e2eArcCoreArtifact.singleFile })
val e2eRuntimeArtifacts = layout.buildDirectory.dir("e2e-runtime")

val prepareE2eRuntimeArtifacts = tasks.register("prepareE2eRuntimeArtifacts") {
    description = "Verifies and stages ARC and the standalone ArcCore provider without starting Paper."
    group = "verification"
    inputs.file(e2eArcJar).optional()
    inputs.file(e2eArcCoreJar)
    inputs.property("arcVersion", e2eArcVersion)
    inputs.property("arcCoreVersion", e2eArcCoreVersion)
    inputs.property("arcCoreSha256", e2eArcCoreSha256)
    outputs.dir(e2eRuntimeArtifacts)
    doLast {
        val arc = requireNotNull(e2eArcJar.orNull) {
            "Supply -Pe2eArcJar=/absolute/path/ARC-$e2eArcVersion.jar from the pinned ARC checkout"
        }
        val core = e2eArcCoreJar.get()
        fun inspect(file: File, expectedName: String, expectedVersion: String): Set<String> {
            require(file.isFile) { "E2E plugin artifact is missing: $file" }
            return ZipFile(file).use { jar ->
                val descriptor = jar.getInputStream(requireNotNull(jar.getEntry("plugin.yml")) {
                    "E2E plugin descriptor is missing: $file"
                }).bufferedReader().use { it.readText() }
                fun value(key: String): String? = descriptor.lineSequence()
                    .firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.trim('\'', '"')
                require(value("name") == expectedName && value("version") == expectedVersion) {
                    "Expected $expectedName $expectedVersion, found ${value("name")} ${value("version")}: $file"
                }
                jar.entries().asSequence().map { it.name }.toSet()
            }
        }
        val arcEntries = inspect(arc, "ARC", e2eArcVersion)
        val coreEntries = inspect(core, "ArcCore", e2eArcCoreVersion)
        val sharedContracts = listOf("ArcSql", "ArcRedis", "ArcMetric", "ArcSchematic")
        require(arcEntries.none { path -> sharedContracts.any { path.startsWith("ru/arc/paper/api/$it") } }) {
            "ARC must consume the standalone ArcCore service API without bundling a second copy"
        }
        require(arcEntries.none { it.startsWith("ru/arc/paper/schematics/") }) {
            "The schematic implementation belongs to ArcCore, not ARC"
        }
        require("ru/arc/paper/api/ArcSchematicService.class" in coreEntries &&
            "ru/arc/paper/schematics/PaperSchematicServiceProvider.class" in coreEntries) {
            "ArcCore must contain the shared schematic API and provider"
        }
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val coreSha256 = sha256(core)
        require(coreSha256 == e2eArcCoreSha256) { "ArcCore E2E artifact checksum mismatch: $coreSha256" }
        val prepared = e2eRuntimeArtifacts.get().asFile.apply { mkdirs() }
        arc.copyTo(prepared.resolve("ARC.jar"), overwrite = true)
        core.copyTo(prepared.resolve("ArcCore.jar"), overwrite = true)
        prepared.resolve("runtime-artifacts.properties").writeText(
            "arc.version=$e2eArcVersion\narc.sha256=${sha256(arc)}\n" +
                "arccore.version=$e2eArcCoreVersion\narccore.sha256=$coreSha256\n",
        )
        logger.lifecycle("E2E runtime artifacts verified: ARC $e2eArcVersion, ArcCore $e2eArcCoreVersion")
    }
}

tasks.withType<me.drownek.plugwright.AbstractPlugwrightTask>().configureEach {
    dependsOn(prepareE2eRuntimeArtifacts)
}

plugwright {
    minecraftVersion.set("1.21.11")
    downloadPlugins {
        url("https://github.com/retrooper/packetevents/releases/download/v2.12.1/packetevents-spigot-2.12.1.jar")
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/OrIs0S6b/LuckPerms-Bukkit-5.5.17.jar")
        url("https://cdn.modrinth.com/data/1u6JkXh5/versions/p8T2aZ8U/worldedit-bukkit-7.4.2.jar")
    }
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    writeFiles {
        file("plugins/ARC.jar", e2eRuntimeArtifacts.get().file("ARC.jar").asFile)
        file("plugins/ArcCore.jar", e2eRuntimeArtifacts.get().file("ArcCore.jar").asFile)
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/ARC/modules/runtime.yml", projectDir.resolve("src/test/e2e/fixtures/arc-runtime.yml"))
        file("plugins/ARC/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/arc-redis.yml"))
        file("plugins/ARC/modules/ops-http.yml", projectDir.resolve("src/test/e2e/fixtures/arc-ops-http.yml"))
        file("plugins/ArcBuilder/modules/builder-tools.yml", projectDir.resolve("src/main/resources/modules/builder-tools.yml").readText()
            .replaceFirst("enabled: false", "enabled: true")
            .replaceFirst("default-locale: ru", "default-locale: en")
            .replaceFirst("require-coreprotect: true", "require-coreprotect: false")
            .replaceFirst("allowed-worlds: []", "allowed-worlds: [world]"))
        file("plugins/ArcBuilder/modules/system-build-books.yml", projectDir.resolve("src/test/e2e/fixtures/system-build-books.yml"))
        file("plugins/ArcBuilder/schematics/e2e.schem", projectDir.resolve("src/test/e2e/fixtures/e2e.schem"))
    }
}
