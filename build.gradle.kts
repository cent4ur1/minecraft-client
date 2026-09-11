plugins {
    java
}

// Zero-dependency build: `java` plugin only, no repositories, no dependencies.
// Everything (agent + injector) compiles against the plain JDK.

group = "com.rottenapple"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The injectable client. `gradle build` produces this.
tasks.named<Jar>("jar") {
    archiveBaseName.set("RottenApple-Agent")
    archiveVersion.set("")
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "com.rottenapple.agent.RottenAppleAgent",
                "Agent-Class" to "com.rottenapple.agent.RottenAppleAgent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true",
                "Implementation-Title" to "RottenApple",
                "Implementation-Version" to project.version
            )
        )
    }
    // injector lives in its own jar; stale Weave metadata stays out
    exclude("rottenapple/launcher/**", "rottenapple.mixins.json")
}

// The tiny attach helper used by launch.sh. JDK-only, no dependencies.
tasks.register<Jar>("launcherJar") {
    archiveBaseName.set("rottenapple-launcher")
    archiveVersion.set("")
    from(sourceSets.main.get().output) {
        include("rottenapple/launcher/**")
    }
}

// Portable package: both jars + launcher script, runnable from anywhere.
tasks.register<Copy>("dist") {
    dependsOn("jar", "launcherJar")
    from(tasks.named("jar"), tasks.named("launcherJar"))
    from(rootDir.resolve("launch.sh")) {
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    into(rootDir.resolve("dist/RottenApple"))
}

tasks.named("build") {
    dependsOn("dist")
}
