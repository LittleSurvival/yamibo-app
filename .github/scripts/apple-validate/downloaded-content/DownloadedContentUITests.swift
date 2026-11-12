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
        var cyan = 0
        var offset = 0
        while offset < pixels.count {
            let red = Int(pixels[offset])
            let green = Int(pixels[offset + 1])
            let blue = Int(pixels[offset + 2])
            // Allow screenshot color-profile conversion while requiring a large cyan image area.
            if red < 80 && green >= 140 && green <= 220 && blue >= 190 {
                cyan += 1
            }
            offset += 4
        }
        XCTAssertGreaterThan(cyan, 100_000, "The synthetic local image must be rendered")
    }

    private func manga(_ mode: String) {
        open(mode)
        let pages = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "300")).firstMatch
        XCTAssertTrue(pages.waitForExistence(timeout: 30), app.debugDescription)
        sleep(2)
        assertLocalImage()
        capture(mode + "-300-local-images")
        let firstPage = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "1 / 300")).firstMatch
        XCTAssertTrue(firstPage.waitForExistence(timeout: 10), app.debugDescription)
        for duration in [0.05, 0.9] {
            for downward in [true, false] {
                let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.985, dy: downward ? 0.30 : 0.70))
                let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.985, dy: downward ? 0.70 : 0.30))
                start.press(forDuration: duration, thenDragTo: end)
                // Preserve the normal image long-press menu; only side seeking is removed.
                let imageMenu = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ["複製", "Copy", "复制"])).firstMatch
                if imageMenu.exists {
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)).tap()
                }
                XCTAssertTrue(firstPage.waitForExistence(timeout: 10), "Side drag changed the page: " + app.debugDescription)
                capture(mode + "-edge-" + String(duration) + (downward ? "-down" : "-up"))
            }
        }
        app.swipeLeft()
        let secondPage = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "2 / 300")).firstMatch
        XCTAssertTrue(secondPage.waitForExistence(timeout: 10), app.debugDescription)
        capture(mode + "-after-navigation")
        app.swipeRight()
        XCTAssertTrue(firstPage.waitForExistence(timeout: 10), app.debugDescription)
        capture(mode + "-normal-backward-page")
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testDownloadedNovel() { novel("novel") }
    func testDownloadedThread() { novel("thread") }
    func testDownloadedThreadManga() { manga("manga") }
    func testDownloadedTagManga() { manga("tag") }
    func testDownloadedRssManga() { manga("rss") }
    func testDownloadedManagement() {
        open("management")
        let tab = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS[c] %@", "內容管理", "内容管理", "downloaded content")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 30), app.debugDescription)
        tab.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA NOVEL")).firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA NOVEL")).firstMatch.tap()
        capture("management-expanded")
        app.swipeUp()
        XCTAssertEqual(app.state, .runningForeground)
    }
    private func text(_ labels: [String]) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", labels)).firstMatch
    }

    func testBatchDeletionFromDefaultFolder() {
        open("management")
        let tab = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@ OR label CONTAINS[c] %@", "內容管理", "内容管理", "downloaded content")).firstMatch
        XCTAssertTrue(tab.waitForExistence(timeout: 30), app.debugDescription)
        tab.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "QA NOVEL")).firstMatch.waitForExistence(timeout: 30), app.debugDescription)
        text(["多選", "Select", "多选"]).tap()
        text(["全選", "Select all", "全选"]).tap()
        XCTAssertTrue(text(["已選取 6 項", "6 selected", "已选择 6 项"]).waitForExistence(timeout: 10), app.debugDescription)
        text(["反選", "Invert selection", "反选"]).tap()
        XCTAssertTrue(text(["已選取 0 項", "0 selected", "已选择 0 项"]).exists, app.debugDescription)
        text(["全選", "Select all", "全选"]).tap()
        text(["刪除下載內容", "Delete downloads", "删除下载内容"]).tap()
        capture("batch-confirmation-six-items")
        text(["取消", "Cancel"]).tap()
        XCTAssertTrue(text(["已選取 6 項", "6 selected", "已选择 6 项"]).exists)
        text(["刪除下載內容", "Delete downloads", "删除下载内容"]).tap()
        text(["確定", "Sure", "确定"]).tap()
        XCTAssertTrue(text(["成功 6 項，失敗 0 項", "6 succeeded; 0 failed", "成功 6 项，失败 0 项"]).waitForExistence(timeout: 60), app.debugDescription)
        XCTAssertTrue(text(["目前沒有已下載內容", "No downloaded content", "当前没有已下载内容"]).waitForExistence(timeout: 15), app.debugDescription)
        capture("batch-six-deletions-complete")
        open("management")
        XCTAssertTrue(tab.waitForExistence(timeout: 30))
        tab.tap()
        XCTAssertTrue(text(["目前沒有已下載內容", "No downloaded content", "当前没有已下载内容"]).waitForExistence(timeout: 30), app.debugDescription)
        capture("batch-deletion-persists-after-relaunch")
    }

}
