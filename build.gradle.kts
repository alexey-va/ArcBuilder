plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ru.ruscrafting"
version = "1.0.16"
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
    implementation("ru.ruscrafting.arc:arc-core:2.4.4")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.4.4")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.4.4")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.4.4")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.4.4")
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.4.4")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.jeff-media:custom-block-data:2.2.4")
    implementation("de.tr7zw:item-nbt-api:2.15.7")

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
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
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.4.4")
    testImplementation("com.sk89q.worldedit:worldedit-bukkit:7.3.18")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.0.3")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.4.4")
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
        relocate("com.github.stefvanschie.inventoryframework", "ru.ruscrafting.builder.libs.inventoryframework")
        relocate("com.jeff_media.customblockdata", "ru.ruscrafting.builder.libs.customblockdata")
        relocate("de.tr7zw.changeme.nbtapi", "ru.ruscrafting.builder.libs.nbtapi")
    }
    check { dependsOn(shadowJar) }
}
