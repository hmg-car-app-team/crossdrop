import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 릴레이 서버 주소: 코드/Git 에 고정하지 않는다. local.properties(`crossdrop.serverUrl`) -> 환경변수 CROSSDROP_SERVER_URL -> 기본값.
//  - release 기본값: http://127.0.0.1:8780 (호스트에서 서버를 띄우고 `adb reverse tcp:8780 tcp:8780`; 서버가 없으면 앱은 한국어 안내와 시연 모드만. 평문은 main network_security_config 의 127.0.0.1/localhost 만)
//  - debug 기본값: 에뮬레이터에서 호스트 PC 로 가는 로컬 주소(10.0.2.2; 평문 허용은 debug 소스셋의 network_security_config)
//  - 값은 `http://127.0.0.1:포트` 처럼 스킴을 포함한 전체 주소여야 한다(스킴 없으면 앱이 거부해 접속하지 않음). 어느 출처가 쓰였는지는 gradle 로그의 '[server-url]' 줄로 확인.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
var serverUrlSource = "default"
val configuredServerUrl: String = (
    localProps.getProperty("crossdrop.serverUrl")?.also { serverUrlSource = "local.properties" }
        ?: providers.gradleProperty("crossdrop.serverUrl").orNull?.also { serverUrlSource = "-P" }
        ?: System.getenv("CROSSDROP_SERVER_URL")?.also { serverUrlSource = "env" }
        ?: ""
    ).trim()
logger.lifecycle("[server-url] cross-drop: source=$serverUrlSource")
val releaseDefaultUrl = "http://127.0.0.1:8780"
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
            buildConfigField("String", "RELAY_SERVER_URL", quoted(if (configuredServerUrl.isEmpty()) releaseDefaultUrl else configuredServerUrl))
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
