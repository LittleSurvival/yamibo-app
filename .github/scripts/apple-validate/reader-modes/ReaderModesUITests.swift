import XCTest
import UIKit

final class ReaderModesUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.yamibo.bbs.readermodesqa")

    private func element(_ labels: [String]) -> XCUIElement {
        let exact = app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", labels)).firstMatch
        if exact.exists { return exact }
        let predicates = labels.map { NSPredicate(format: "label CONTAINS[c] %@", $0) }
        return app.descendants(matching: .any).matching(NSCompoundPredicate(orPredicateWithSubpredicates: predicates)).firstMatch
    }

    private func tap(_ labels: [String]) {
        let target = element(labels)
        for _ in 0..<4 {
            if target.waitForExistence(timeout: 2), target.isHittable { target.tap(); return }
            app.swipeUp()
        }
        XCTFail("Missing control " + labels.joined(separator: ", ") + " " + app.debugDescription)
    }

    private func openDownload(_ labels: [String]) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(zh-Hant)", "-AppleLocale", "zh_TW"]
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let alert = springboard.alerts.firstMatch
        if alert.waitForExistence(timeout: 3) { alert.buttons.firstMatch.tap() }
        tap(["我的", "Profile"])
        tap(["下載管理", "下载管理", "Download manager", "Downloads"])
        tap(["下載內容管理", "下载内容管理", "Downloaded content", "內容管理"])
        tap(["QA Three Readers"])
        tap(["第 1 頁", "第 1 页", "Page 1"])
        XCTAssertTrue(element(["開啟下載內容", "打开下载内容", "Open downloaded content"]).waitForExistence(timeout: 10), app.debugDescription)
        tap(labels)
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testNovel() {
        openDownload(["小說閱讀", "小说阅读", "Novel reader"])
        XCTAssertTrue(element(["READER_FLOOR_1"]).waitForExistence(timeout: 20), app.debugDescription)
        capture("novel-first-author-floor")
    }

    func testForum() {
        openDownload(["論壇模式", "论坛模式", "Forum mode"])
        XCTAssertTrue(element(["READER_FLOOR_1"]).waitForExistence(timeout: 20), app.debugDescription)
        capture("forum-first-author-floor")
    }

    func testComic() {
        openDownload(["智能漫畫", "智能漫画", "Smart Comic"])
        XCTAssertTrue(element(["1 / 3"]).waitForExistence(timeout: 20), app.debugDescription)
        let painted = NSPredicate { _, _ in self.localMarkerIsVisible() }
        expectation(for: painted, evaluatedWith: app)
        waitForExpectations(timeout: 15)
        capture("comic-first-painted-image")
        app.swipeLeft()
        XCTAssertTrue(element(["2 / 3"]).waitForExistence(timeout: 8), app.debugDescription)
        capture("comic-second-floor")
    }

    private func localMarkerIsVisible() -> Bool {
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
        return cyan > 100_000
    }

}
