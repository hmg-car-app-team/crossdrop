import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 릴레이 서버 주소: 코드/Git 에 고정하지 않는다. local.properties(`crossdrop.serverUrl`) -> 환경변수 CROSSDROP_SERVER_URL -> 기본값.
//  - release 기본값: 빈 문자열(= 서버와 통신하지 않음, 시연 모드만)
//  - debug 기본값: 에뮬레이터에서 호스트 PC 로 가는 로컬 주소(10.0.2.2; 평문 HTTP 허용은 debug 소스셋의 network_security_config 만)
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val configuredServerUrl: String = (localProps.getProperty("crossdrop.serverUrl") ?: System.getenv("CROSSDROP_SERVER_URL") ?: "").trim()
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.hmgcarapp.crossdrop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hmgcarapp.crossdrop"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            buildConfigField("String", "RELAY_SERVER_URL", quoted(configuredServerUrl.ifEmpty { "http://10.0.2.2:8780" }))
        }
        release {
            buildConfigField("String", "RELAY_SERVER_URL", quoted(configuredServerUrl))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.pleos.navihelper)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
