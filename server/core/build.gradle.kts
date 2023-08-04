/*
 * This file was generated by the Gradle "init" task.
 *
 * This generated file contains a sample Java Library project to get you started.
 * For more details take a look at the Java Libraries chapter in the Gradle
 * User Manual available at https://docs.gradle.org/6.3/userguide/java_library_plugin.html
 */
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")
	`java-library`
}

// FIXME: Please replace these to Java 11 as that's what they actually are
kotlin {
	jvmToolchain {
		languageVersion.set(JavaLanguageVersion.of(17))
	}
}
java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(17))
	}
}
tasks.withType<KotlinCompile> {
	kotlinOptions.jvmTarget = "17"
}

// Set compiler to use UTF-8
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
tasks.withType<Test> {
	systemProperty("file.encoding", "UTF-8")
}
tasks.withType<Javadoc> {
	options.encoding = "UTF-8"
}

allprojects {
	repositories {
		// Use jcenter for resolving dependencies.
		// You can declare any Maven/Ivy/file repository here.
		mavenCentral()
	}
}

dependencies {
	implementation(project(":solarxr-protocol"))

	// This dependency is used internally,
	// and not exposed to consumers on their own compile classpath.
	implementation("com.google.flatbuffers:flatbuffers-java:22.10.26")
	implementation("commons-cli:commons-cli:1.5.0")
	implementation("com.fasterxml.jackson.core:jackson-databind:2.15.1")
	implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.1")

	implementation("com.github.jonpeterson:jackson-module-model-versioning:1.2.2")
	implementation("org.apache.commons:commons-math3:3.6.1")
	implementation("org.apache.commons:commons-lang3:3.12.0")
	implementation("org.apache.commons:commons-collections4:4.4")

	implementation("com.illposed.osc:javaosc-core:0.8")
	implementation("org.java-websocket:Java-WebSocket:1.+")
	implementation("com.melloware:jintellitype:1.+")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
	implementation("it.unimi.dsi:fastutil:8.5.12")
	implementation("org.jmdns:jmdns:3.5.8")

	testImplementation(kotlin("test"))
	// Use JUnit test framework
	testImplementation(platform("org.junit:junit-bom:5.9.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.junit.platform:junit-platform-launcher")
}
tasks.test {
	useJUnitPlatform()
}
