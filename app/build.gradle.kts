import java.net.URL
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

dependencies {
    compileOnly(project(":hideapi"))

    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

val geoFilesDownloadDir = "src/main/assets"

task("downloadGeoFiles") {

    val geoFiles = mapOf(
        "geoip.metadb" to "geoip.metadb",
        "geosite.dat" to "geosite.dat",
        "GeoLite2-ASN.mmdb" to "ASN.mmdb",
        "BundleMRS.7z" to "BundleMRS.7z",
    )

    doLast {
        geoFiles.forEach { (remoteFileName, outputFileName) ->
            val outputPath = file("$geoFilesDownloadDir/$outputFileName")
            outputPath.parentFile.mkdirs()

            // Keep a previously downloaded non-empty file when the network is unavailable.
            if (outputPath.isFile && outputPath.length() > 1024L) {
                println("$outputFileName already exists, skipping download")
                return@forEach
            }

            val candidates = listOf(
                "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/$remoteFileName",
                "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/$remoteFileName",
            )
            val temporaryPath = file("${outputPath.absolutePath}.download")
            var lastFailure: Throwable? = null

            for (downloadUrl in candidates) {
                try {
                    println("Downloading $outputFileName from $downloadUrl")
                    val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = true
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 120_000
                    connection.setRequestProperty("User-Agent", "ClashMetaForAndroid-Gradle")
                    connection.inputStream.use { input ->
                        Files.copy(input, temporaryPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    connection.disconnect()
                    check(temporaryPath.length() > 1024L) { "$outputFileName download is empty or incomplete" }
                    Files.move(temporaryPath.toPath(), outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    println("$outputFileName downloaded to $outputPath")
                    lastFailure = null
                    break
                } catch (failure: Throwable) {
                    lastFailure = failure
                    temporaryPath.delete()
                    logger.warn("Failed to download $outputFileName from $downloadUrl: ${failure.message}")
                }
            }
            if (lastFailure != null) throw GradleException("Unable to download $outputFileName from all official sources", lastFailure)
        }
    }
}

afterEvaluate {
    val downloadGeoFilesTask = tasks["downloadGeoFiles"]

    tasks.forEach {
        if (it.name.startsWith("assemble")) {
            it.dependsOn(downloadGeoFilesTask)
        }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(file(geoFilesDownloadDir))
}
