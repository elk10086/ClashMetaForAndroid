@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    fun queryConfigProperty(key: String): Any? {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        } else {
            return null
        }
        return localProperties.getProperty(key)
    }

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                val customApplicationId = queryConfigProperty("custom.application.id") as? String?
                applicationId = customApplicationId.takeIf { it?.isNotBlank() == true } ?: "com.github.metacubex.clash"
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            minSdk = 21
            targetSdk = 35

            versionName = "2.11.32"
            versionCode = 211032

            resValue("string", "release_name", "v$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }

            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "cmfa-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                }
            }
        }

        productFlavors {
            if (isApp) flavorDimensions("brand", "feature") else flavorDimensions("feature")

            val removeSuffix = (queryConfigProperty("remove.suffix") as? String)?.toBoolean() == true

            create("alpha") {
                isDefault = true
                dimension = "feature"
                if (!removeSuffix && !isApp) {
                    versionNameSuffix = ".Alpha"
                }


                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                resValue("string", "launch_name", "@string/launch_name_alpha")
                resValue("string", "application_name", "@string/application_name_alpha")

            }

            create("meta") {
                dimension = "feature"
                if (!removeSuffix && !isApp) {
                    versionNameSuffix = ".Meta"
                }

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                resValue("string", "launch_name", "@string/launch_name_meta")
                resValue("string", "application_name", "@string/application_name_meta")

            }

            if (isApp) {
                fun com.android.build.gradle.internal.dsl.ProductFlavor.brandConfig(
                    id: String,
                    displayName: String,
                    appId: String,
                    brandVersionName: String,
                    brandVersionCode: Int,
                    officialSiteUrl: String,
                    officialSiteHost: String,
                    customerServiceUrl: String,
                    configUrls: String,
                ) {
                    dimension = "brand"
                    applicationId = appId
                    versionName = brandVersionName
                    versionCode = brandVersionCode
                    resValue("string", "launch_name", displayName)
                    resValue("string", "application_name", displayName)
                    buildConfigField("String", "BRAND_ID", "\"$id\"")
                    buildConfigField("String", "BRAND_DISPLAY_NAME", "\"$displayName\"")
                    buildConfigField("String", "BRAND_VERSION_NAME", "\"$brandVersionName\"")
                    buildConfigField("String", "BRAND_OFFICIAL_SITE_URL", "\"$officialSiteUrl\"")
                    buildConfigField("String", "BRAND_OFFICIAL_SITE_HOST", "\"$officialSiteHost\"")
                    buildConfigField("String", "BRAND_CUSTOMER_SERVICE_URL", "\"$customerServiceUrl\"")
                    buildConfigField("String", "BRAND_CONFIG_URLS", "\"$configUrls\"")
                }

                create("zy") {
                    brandConfig(
                        "zy", "ZY", "com.flutterfly.zhuiyun", "6.6.3", 2026080401,
                        "https://www.zhuiyunjiasu.xyz", "www.zhuiyunjiasu.xyz",
                        "https://go.crisp.chat/chat/embed/?website_id=45c82007-8e52-44e6-8ded-31db17a83c4f",
                        "https://zyxb.oss-cn-wuhan-lr.aliyuncs.com/zy_checkVersion.txt|https://pub-d51a46c1ed05483887d9cbbc4ea8d40a.r2.dev/zy_checkVersion.txt|https://raw.gitcode.com/lishiming123/zzzzzzzzzzzzzzzzyyyyyy/raw/main/zy_checkVersion.txt|https://wudidefastyyds-1401942160.cos.ap-chengdu.myqcloud.com/zy_checkVersion.txt",
                    )
                }
                create("fly") {
                    brandConfig(
                        "fastfly", "Fastfly", "com.flutterfly.fastfly", "2.6.3", 2026074301,
                        "https://fastfly.club", "fastfly.club",
                        "https://go.crisp.chat/chat/embed/?website_id=36c7c66a-f768-4354-9823-5aaefec60c81",
                        "https://zyxb.oss-cn-wuhan-lr.aliyuncs.com/fly_checkVersion.txt|https://raw.gitcode.com/lishiming123/zzzzzzzzzzzzzzzzyyyyyy/raw/main/fly_checkVersion.txt|https://pub-d51a46c1ed05483887d9cbbc4ea8d40a.r2.dev/fly_checkVersion.txt|https://wudidefastyyds-1401942160.cos.ap-chengdu.myqcloud.com/fly_checkVersion.txt",
                    )
                }
                create("carx") {
                    brandConfig(
                        "carx", "CarX", "com.zz.car", "1.0.0", 2026050200,
                        "https://carx.one", "carx.one",
                        "https://go.crisp.chat/chat/embed/?website_id=45c82007-8e52-44e6-8ded-31db17a83c4f",
                        "https://zyxb.oss-cn-wuhan-lr.aliyuncs.com/car_checkVersion.txt|https://pub-d51a46c1ed05483887d9cbbc4ea8d40a.r2.dev/car_checkVersion.txt|https://raw.gitcode.com/lishiming123/zzzzzzzzzzzzzzzzyyyyyy/raw/main/car_checkVersion.txt|https://wudidefastyyds-1401942160.cos.ap-chengdu.myqcloud.com/zy_checkVersion.txt",
                    )
                }
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val keystore = rootProject.file("signing.properties")
            if (keystore.exists()) {
                create("release") {
                    val prop = Properties().apply {
                        keystore.inputStream().use(this::load)
                    }

                    storeFile = rootProject.file("release.keystore")
                    storePassword = prop.getProperty("keystore.password")!!
                    keyAlias = prop.getProperty("key.alias")!!
                    keyPassword = prop.getProperty("key.password")!!
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                signingConfig = signingConfigs.findByName("release") ?: signingConfigs["debug"]
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ".debug"
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
                    isUniversalApk = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}
