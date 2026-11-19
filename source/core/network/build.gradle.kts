plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
}

android {
    namespace = "com.xayah.core.network"
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("gopeed/jni"))
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:util"))
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    implementation(project(":core:rootservice"))

    // OkHttp
    implementation(libs.okhttp)
    implementation(files("libs/gopeed-classes.jar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core.jvm)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Gson
    implementation(libs.gson)

    // Backends
    implementation(libs.apache.commons.net)
    implementation(libs.smbj) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation(libs.guava.compat)
    implementation(libs.smbj.rpc) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "com.hierynomus", module = "smbj")
    }
    implementation(libs.sshj)
    implementation(libs.sardine.next)

    // PickYou
    implementation(libs.pickyou)
}