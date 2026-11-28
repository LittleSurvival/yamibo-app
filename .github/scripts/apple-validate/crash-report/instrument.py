"""Insert validation probes into a disposable runner checkout."""
from pathlib import Path

def insert(path, expected, replacement):
    path = Path(path)
    source = path.read_text(encoding="utf-8")
    if source.count(expected) != 1:
        raise RuntimeError(f"Unexpected source in {path}: {expected}")
    path.write_text(source.replace(expected, replacement), encoding="utf-8")

# A Kotlin exception crossing the ObjC boundary without @Throws is genuinely fatal.
# Only the external, standalone simctl launch supplies this environment variable.
base = Path("composeApp/src/iosMain/kotlin/me/thenano/yamibo/yamibo_app")
(base / "CrashQa.kt").write_text("""package me.thenano.yamibo.yamibo_app

fun crashForValidation(marker: String) {
    throw IllegalStateException(marker)
}
""", encoding="utf-8")
hook = "CrashReportStore.shared.install()"
insert("iosApp/iosApp/iOSApp.swift", hook, hook + """
        if let marker = ProcessInfo.processInfo.environment["QA_CRASH_TRIGGER"] {
            CrashQaKt.crashForValidation(marker: marker)
        }""")
# Remove the temporary obstacle after SwiftUI displays the real filesystem error.
failure_id = '.accessibilityIdentifier("crashDismissError")'
insert("iosApp/iosApp/CrashReportView.swift", failure_id, failure_id + """
                    .onAppear {
                        let obstacle = URL.applicationSupportDirectory.appendingPathComponent("CrashReports/viewed.json")
                        if FileManager.default.fileExists(atPath: obstacle.appendingPathComponent("qa-obstacle").path) {
                            try! FileManager.default.removeItem(at: obstacle)
                        }
                    }""")
path = "iosApp/iosApp/ContentView.swift"
insert(path, "        ReaderHostController()", """
        if let phase = ProcessInfo.processInfo.environment["QA_CRASH_LAUNCH"] {
            let marker = FileManager.default.temporaryDirectory.appendingPathComponent("normal-host-" + phase)
            if !FileManager.default.fileExists(atPath: marker.path) {
                try! String(Date().timeIntervalSince1970).write(to: marker, atomically: true, encoding: .utf8)
            }
        }
        return ReaderHostController()""")
insert(path, "        ComposeView()\n            .ignoresSafeArea()", """        ComposeView()
            .ignoresSafeArea()
            .overlay(alignment: .bottomTrailing) {
                Text("QA").accessibilityIdentifier("qaNormalHost")
            }""")
