import XCTest

final class DownloadedContentUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.yamibo.bbs.downloadqa")

    private func open(_ mode: String) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-qa-reader", mode]
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for _ in 0..<3 {
            if springboard.alerts.firstMatch.exists {
                let allow = springboard.alerts.buttons["Allow"]
                if allow.exists { allow.tap() } else { springboard.alerts.buttons.firstMatch.tap() }
            }
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.3))
        }
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func novel(_ mode: String) {
        open(mode)
        let body = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "QA_NOVEL_START")).firstMatch
        XCTAssertTrue(body.waitForExistence(timeout: 30), app.debugDescription)
        capture(mode + "-local-content")
        app.swipeUp()
        capture(mode + "-scrolled")
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func manga(_ mode: String) {
        open(mode)
        let pages = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "300")).firstMatch
        XCTAssertTrue(pages.waitForExistence(timeout: 30), app.debugDescription)
        capture(mode + "-300-local-images")
        app.swipeLeft()
        capture(mode + "-after-navigation")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testDownloadedNovel() { novel("novel") }
    func testDownloadedThread() { novel("thread") }
    func testDownloadedThreadManga() { manga("manga") }
    func testDownloadedTagManga() { manga("tag") }
    func testDownloadedRssManga() { manga("rss") }
    func testDownloadedManagement() {
        open("management")
        let tab = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS[c] %@", "內容管理", "content management")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 30), app.debugDescription)
        tab.tap()
        XCTAssertTrue(app.staticTexts.matching(identifier: "QA NOVEL").firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.staticTexts.matching(identifier: "QA NOVEL").firstMatch.tap()
        capture("management-expanded")
        app.swipeUp()
        XCTAssertEqual(app.state, .runningForeground)
    }
}
