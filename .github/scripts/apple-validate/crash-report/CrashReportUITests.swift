import XCTest

final class CrashReportUITests: XCTestCase {
    func testDefaultRecovery() throws { try verifyRecovery(theme: "DEFAULT") }
    func testClassicBlackRecovery() throws { try verifyRecovery(theme: "CLASSIC_BLACK") }

    private func verifyRecovery(theme: String) throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-appsettings.themescheme", theme]
        app.launchEnvironment["QA_CRASH_LAUNCH"] = "recovery"
        app.launch()
        func element(_ id: String) -> XCUIElement {
            app.descendants(matching: .any).matching(identifier: id).firstMatch
        }
        func capture(_ name: String) {
            let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            shot.name = "\(theme)-\(name)"
            shot.lifetime = .keepAlways
            add(shot)
        }
        let title = element("crashTitle")
        XCTAssertTrue(title.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertFalse(element("qaNormalHost").exists)
        XCTAssertFalse(element("crashDetails").exists)
        XCTAssertTrue(element("crashUpload").exists)
        XCTAssertFalse(element("crashUpload").isEnabled)
        capture("recovery")
        let imagePath = FileManager.default.temporaryDirectory.appendingPathComponent("recovery-\(theme).png")
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: imagePath)
        element("crashDisclosure").tap()
        let details = element("crashDetails")
        XCTAssertTrue(details.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue((details.label + String(describing: details.value)).contains("Kotlin/Native"), app.debugDescription)
        capture("details")
        element("crashDisclosure").tap()
        XCTAssertFalse(details.exists)
        element("crashCopy").tap()
        XCTAssertTrue(element("crashCopyFeedback").waitForExistence(timeout: 3), app.debugDescription)
        capture("copied")
        element("crashClose").tap()
        let failure = element("crashDismissError")
        XCTAssertTrue(failure.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(title.exists)
        XCTAssertFalse(element("qaNormalHost").exists)
        capture("dismiss-error")
        let clock = String(Date().timeIntervalSince1970)
        let beforeDismiss = FileManager.default.temporaryDirectory.appendingPathComponent("before-dismiss-\(theme)")
        try clock.write(to: beforeDismiss, atomically: true, encoding: .utf8)
        element("crashClose").tap()
        let normal = element("qaNormalHost")
        XCTAssertTrue(normal.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(title.exists)
        capture("dismissed")
        app.terminate()
        app.launchEnvironment["QA_CRASH_LAUNCH"] = "relaunch"
        app.launch()
        XCTAssertTrue(normal.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertFalse(title.exists)
        capture("relaunch")
    }
}
