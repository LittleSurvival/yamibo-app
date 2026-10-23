import XCTest
import UIKit

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
        let body = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA_NOVEL_START")).firstMatch
        XCTAssertTrue(body.waitForExistence(timeout: 30), app.debugDescription)
        capture(mode + "-local-content")
        app.swipeUp()
        capture(mode + "-scrolled")
        XCTAssertEqual(app.state, .runningForeground)
    }

    private func assertLocalImage() {
        let source = app.screenshot().image.cgImage!
        let width = source.width, height = source.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(source, in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
        }
        let cyan = stride(from: 0, to: pixels.count, by: 4).filter {
            (14...26).contains(Int(pixels[$0])) && (174...186).contains(Int(pixels[$0 + 1])) && (214...226).contains(Int(pixels[$0 + 2]))
        }.count
        XCTAssertGreaterThan(cyan, 100_000, "The synthetic local image must be rendered")
    }

    private func manga(_ mode: String) {
        open(mode)
        let pages = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "300")).firstMatch
        XCTAssertTrue(pages.waitForExistence(timeout: 30), app.debugDescription)
        sleep(2)
        assertLocalImage()
        capture(mode + "-300-local-images")
        app.swipeLeft()
        let secondPage = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "2 / 300")).firstMatch
        XCTAssertTrue(secondPage.waitForExistence(timeout: 10), app.debugDescription)
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
        let tab = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS[c] %@", "內容管理", "content management")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 30), app.debugDescription)
        tab.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA NOVEL")).firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA NOVEL")).firstMatch.tap()
        capture("management-expanded")
        app.swipeUp()
        XCTAssertEqual(app.state, .runningForeground)
    }
}
