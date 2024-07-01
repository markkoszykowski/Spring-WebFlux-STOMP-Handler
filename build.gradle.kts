plugins {
	idea
	`java-library`
	checkstyle
	libs.plugins.spring.boot
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

	compileOnly(libs.lombok)
	annotationProcessor(libs.lombok)

	implementation(libs.bundles.spring.webflux.websocket)
	implementation(libs.agrona)

	testImplementation(platform(libs.cucumber.dependencies))
	testImplementation(platform(libs.junit.dependencies))
	testImplementation(libs.bundles.testing)
}

tasks.test {
	useJUnitPlatform()
}
