plugins {
	id("java-library")
	id("checkstyle")
	id("idea")
}

idea {
	module {
		isDownloadJavadoc = true
		isDownloadSources = true
	}
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

checkstyle {
	toolVersion = libs.versions.checkstyle.get()
}

group = "com.github.stomp"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	annotationProcessor(platform(libs.spring.dependencies))
	implementation(platform(libs.spring.dependencies))

	annotationProcessor(libs.lombok)
	compileOnly(libs.lombok)

	implementation(libs.bundles.spring.webflux.websocket)
	implementation(libs.agrona)

	testImplementation(platform(libs.cucumber.dependencies))
	testImplementation(platform(libs.junit.dependencies))
	testImplementation(libs.bundles.testing)
	testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
	useJUnitPlatform()
}
