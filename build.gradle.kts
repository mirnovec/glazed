plugins {
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
}

base {
    archivesName = properties["archives_base_name"] as String
    version = properties["mod_version"] as String
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    maven {
        url = uri("https://jitpack.io")
    }
    maven {
        name = "Bawnorton"
        url = uri("https://maven.bawnorton.com/releases")
    }
    maven {
        // Hosts nether-pathfinder, which Baritone's elytra process loads on startup.
        name = "babbaj"
        url = uri("https://babbaj.github.io/maven/")
    }
    // loom can only nest a real module, not a loose file, so the bundled jar lives here
    flatDir {
        dirs("libs")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${properties["minecraft_version"] as String}")
    // 26.x ships unobfuscated so there is no mappings() line and no mod* configurations;
    // loom 1.16+ remaps automatically.
    implementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    implementation("meteordevelopment:meteor-client:${properties["meteor_version"] as String}-SNAPSHOT")
    implementation("meteordevelopment:baritone:${properties["baritone_version"] as String}-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.10.1")
    // ExploitPreventer by NikOverflow (MIT), shipped inside our jar so it always loads.
    // not added to the dev runtime because it hard-depends on the full fabric-api and we only
    // pull the few modules IAS needs
    include("com.nikoverflow:exploitpreventer:1.1.0")
    // Baritone touches NetherPathfinder during BaritoneAPI's static init, so the dev client
    // crashes on startup without it. Runtime-only: it is not bundled into the released jar.
    runtimeOnly("dev.babbaj:nether-pathfinder:1.4.1")
    // ExploitPreventer hard-depends on the whole fabric-api, so dev needs all of it, not just the
    // couple of modules IAS wanted
    runtimeOnly("net.fabricmc.fabric-api:fabric-api:${properties["fabric_api_version"] as String}")
    runtimeOnly("com.nikoverflow:exploitpreventer:1.1.0")
    // exploitpreventer carries these two inside itself. fabric unpacks nested jars in a real
    // install but not for a dev mod, so without these it dies on its own IAPI class
    runtimeOnly("com.nikoverflow:ExploitPreventer-API:1.0.0")
    runtimeOnly("dev.lukebemish:opensesame-core:0.8.1")
    include(implementation(annotationProcessor("com.github.bawnorton.mixinsquared:mixinsquared-fabric:0.3.7-beta.1")!!)!!)
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to project.property("minecraft_version"),
        )
        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }
    jar {
        val licenseSuffix = project.base.archivesName.get()
        from("LICENSE") {
            rename { "${it}_${licenseSuffix}" }
        }
    }
    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 25
        options.compilerArgs.addAll(listOf("-Xmaxerrs", "10000"))
    }
}
