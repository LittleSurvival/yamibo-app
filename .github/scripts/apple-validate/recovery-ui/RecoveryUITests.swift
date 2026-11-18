import XCTest

final class RecoveryUITests: XCTestCase {
    let app = XCUIApplication()
    let retryNames = ["重試", "重试", "Try again", "Retry"]
    let reloadNames = ["重新載入", "重新载入", "重新加载", "Reload"]
    let waitNames = ["等待網路連線", "等待网络连接", "Waiting for a connection"]

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    func launch(_ scenario: String) {
        app.launchEnvironment["YAMIBO_QA_CASE"] = scenario
        app.launch()
    }

    func label(_ words: [String], contains: Bool = false) -> XCUIElement {
        let tests = words.map { NSPredicate(format: contains ? "label CONTAINS[c] %@" : "label ==[c] %@", $0) }
        return app.descendants(matching: .any).matching(NSCompoundPredicate(orPredicateWithSubpredicates: tests)).firstMatch
    }

    func see(_ words: [String], contains: Bool = false, timeout: TimeInterval = 18,
             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(label(words, contains: contains).waitForExistence(timeout: timeout),
                      "Missing \(words)\n\(app.debugDescription)", file: file, line: line)
    }

    func shot(_ name: String) {
        // Compose can publish accessibility updates one frame before Metal presents them.
        Thread.sleep(forTimeInterval: 0.7)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func tapVisible(_ words: [String]) {
        see(words)
        let element = label(words)
        XCTAssertTrue(element.isHittable)
        let frame = element.frame
        XCTAssertTrue(app.frame.contains(frame), "Outside viewport \(frame)")
        // Tap the current screen coordinates; XCTest must not scroll the target into view.
        app.coordinate(withNormalizedOffset: .zero).withOffset(
            CGVector(dx: frame.midX - app.frame.minX, dy: frame.midY - app.frame.minY)).tap()
    }

    func testLongCardAction() {
        launch("card")
        see(["QA attempts=0"])
        shot("long-card-large-text")
        tapVisible(retryNames)
        see(["QA attempts=1"])
    }

    func testExpandedDiagnosticsAction() {
        launch("diagnostic")
        see(retryNames)
        let retry = label(retryNames)
        XCTAssertTrue(retry.isHittable && app.frame.contains(retry.frame))
        let details = ["查看診斷詳情", "查看诊断详情", "Show diagnostic details"]
        let title = label(["載入失敗", "加载失败", "Loading failed"])
        let origin = app.coordinate(withNormalizedOffset: .zero)
        // Scroll the optional message pane, leaving the recovery action in its original position.
        if !label(details).exists || !label(details).isHittable {
            let start = origin.withOffset(CGVector(dx: retry.frame.midX, dy: retry.frame.minY - 20))
            let end = origin.withOffset(CGVector(dx: retry.frame.midX, dy: title.frame.minY + 4))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        tapVisible(details)
        see(["收起診斷詳情", "收起诊断详情", "Hide diagnostic details"])
        shot("expanded-diagnostics")
        tapVisible(retryNames)
        see(["QA attempts=1"])
    }

    func testInlineImageAction() {
        launch("image")
        see(["QA attempts=0"])
        see(["Fixture text"], contains: true)
        let message = label(["Fixture text"], contains: true)
        XCTAssertGreaterThan(message.frame.height, 18)
        XCTAssertLessThanOrEqual(message.frame.maxX, label(reloadNames).frame.minX)
        shot("compact-inline-image")
        tapVisible(reloadNames)
        see(["QA attempts=1"])
    }

    func testLandscapeImageAction() {
        launch("image")
        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertGreaterThan(app.frame.width, app.frame.height)
        shot("landscape-inline-image")
        tapVisible(reloadNames)
        see(["QA attempts=1"])
    }

    func testFavoriteStatusAction() {
        launch("favorite")
        see(["QA attempts=0"])
        shot("favorite-status-large-text")
        tapVisible(["隱藏", "隐藏", "Hide"])
        see(["QA attempts=1"])
    }

    func testLinkAction() {
        launch("link")
        see(["QA attempts=1"])
        shot("link-error-large-text")
        tapVisible(retryNames)
        see(["QA attempts=2"])
    }

    func testHistoryAction() {
        launch("history")
        see(["QA attempts=1"])
        see(retryNames)
        shot("history-error-large-text")
        tapVisible(retryNames)
        see(["QA attempts=2"])
    }

    func testHomeRecoversWithoutTap() {
        launch("home-restore")
        see(waitNames, contains: true)
        XCTAssertFalse(label(["載入失敗", "加载失败", "Loading failed"]).exists)
        shot("waiting-for-network")
        see(["QA fresh"], contains: true)
        see(["QA attempts=2"])
        shot("home-recovered")
    }

    func testCachedHomeRefreshesWithoutTap() {
        launch("home-cache")
        see(["QA cached"], contains: true)
        XCTAssertFalse(label(waitNames, contains: true).exists)
        see(["QA fresh"], contains: true)
        see(["QA attempts=2"])
    }

    func testRecoveryBeforeFailureCompletes() {
        launch("home-race")
        see(["QA fresh"], contains: true)
        see(["QA attempts=2"])
    }

    func testPersistentOfflineHasManualRetry() {
        launch("home-offline")
        see(waitNames, contains: true)
        sleep(3)
        see(["QA attempts=1"])
        tapVisible(retryNames)
        see(["QA fresh"], contains: true)
        see(["QA attempts=2"])
    }

    func testSamePathDoesNotLoopAndForegroundRecovers() {
        launch("home-resume")
        see(["QA attempts=2"])
        sleep(2)
        see(["QA attempts=2"])
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
        see(["QA fresh"], contains: true)
        see(["QA attempts=3"])
    }

    func testBusinessFailureNeverReplaysOnPathChange() {
        launch("home-business")
        see(["QA business failure"], contains: true)
        sleep(8)
        see(["QA attempts=1"])
        XCTAssertFalse(label(waitNames, contains: true).exists)
    }

    func testNativePathMonitorReportsSimulatorConnectivity() {
        launch("native")
        see(["QA native path=true"])
    }
}
