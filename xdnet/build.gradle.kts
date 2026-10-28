plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
    signing
}

android {
    namespace = "com.xd.network"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// ---------- Maven 发布配置 ----------
// groupId / version / POM 元数据在根目录 gradle.properties 中维护。
// 签名密钥与 Sonatype 凭据放在本机 ~/.gradle/gradle.properties（不要提交到 Git）：
//   signing.keyId=xxxxxxxx
//   signing.password=xxxx
//   signing.secretKeyRingFile=/Users/you/.gnupg/secring.gpg
//   ossrhUsername=Central Portal 的 token username
//   ossrhPassword=Central Portal 的 token password

val pomName = findProperty("POM_NAME") as String? ?: "XdNet"
val pomDescription = findProperty("POM_DESCRIPTION") as String? ?: ""
val pomUrl = findProperty("POM_URL") as String? ?: ""
val pomLicenseName = findProperty("POM_LICENSE_NAME") as String? ?: ""
val pomLicenseUrl = findProperty("POM_LICENSE_URL") as String? ?: ""
val pomDeveloperId = findProperty("POM_DEVELOPER_ID") as String? ?: ""
val pomDeveloperName = findProperty("POM_DEVELOPER_NAME") as String? ?: ""
val pomScmUrl = findProperty("POM_SCM_URL") as String? ?: ""
val pomScmConnection = findProperty("POM_SCM_CONNECTION") as String? ?: ""

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = findProperty("GROUP") as String? ?: "com.xd"
                artifactId = findProperty("POM_ARTIFACT_ID") as String? ?: "xd-net"
                version = findProperty("VERSION_NAME") as String? ?: "1.0.0"

                pom {
                    name.set(pomName)
                    description.set(pomDescription)
                    url.set(pomUrl)
                    licenses {
                        license {
                            name.set(pomLicenseName)
                            url.set(pomLicenseUrl)
                        }
                    }
                    developers {
                        developer {
                            id.set(pomDeveloperId)
                            name.set(pomDeveloperName)
                        }
                    }
                    scm {
                        url.set(pomUrl)
                        connection.set(pomScmConnection)
                    }
                }
            }
        }
        repositories {
            maven {
                name = "sonatype"
                val releasesUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if ((findProperty("VERSION_NAME") as String? ?: "").endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl)
                credentials {
                    username = findProperty("ossrhUsername") as String? ?: System.getenv("OSSRH_USERNAME")
                    password = findProperty("ossrhPassword") as String? ?: System.getenv("OSSRH_PASSWORD")
                }
            }
        }
    }

    signing {
        // 上传到本地 Maven（publishToMavenLocal）时跳过签名，方便本地联调
        setRequired({ gradle.taskGraph.allTasks.none { it.name.contains("MavenLocal") } })
        sign(publishing.publications["release"])
    }
}
