import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "dev.sushnt.journal.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Journal" // name in the Start menu and the install folder
            packageVersion = "1.1.0" // keep in step with AppInfo.kt; raise for every new installer
            description = "A quiet journal that syncs between your phone and computer"
            vendor = "Sushant Pulipati"
            modules(
                "jdk.httpserver",  // the small local web server used by "Sign in with Google"
                "jdk.crypto.ec",   // HTTPS to the server
                "java.sql",
                "jdk.unsupported",
            )
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "Journal"
                shortcut = true      // desktop shortcut
                dirChooser = true    // let the installer ask where to install
                perUserInstall = true // no admin rights needed
                // Keep this the same forever: it lets a newer installer upgrade the old one.
                upgradeUuid = "6f4b2d0e-8c1a-4f5e-9b37-2a6d9e1c7f40"
            }
        }
    }
}