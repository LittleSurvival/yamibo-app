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
        let deadline = Date().addingTimeInterval(20)
        repeat {
            let later = app.buttons.matching(NSPredicate(format: "label IN %@", ["稍後", "稍后", "Later"])).firstMatch
            if later.exists && later.isHittable { later.tap() }
            let target = element(labels)
            if target.waitForExistence(timeout: 1), target.isHittable { target.tap(); return }
        }
        while Date() < deadline
        XCTFail("Missing control " + labels.joined(separator: ", ") + " " + app.debugDescription)
    }

    private func openDownload(_ labels: [String], dark: Bool, damaged: Bool = false, migrateInManager: Bool = true) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(zh-Hant)", "-AppleLocale", "zh_TW"]
        // SettingsRegistry stores delegated property names in lowercase.
        if dark { app.launchArguments += ["-appsettings.themescheme", "DEFAULT_DARK"] }
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let alert = springboard.alerts.firstMatch
        if alert.waitForExistence(timeout: 3) { alert.buttons.firstMatch.tap() }
        tap(["我的", "Profile"])
        tap(["下載管理", "下载管理", "Download manager", "Downloads"])
        tap(["下載內容管理", "下载内容管理", "Downloaded content", "內容管理"])
        if migrateInManager {
            tap(["轉換舊版圖片", "转换旧版图片", "Convert legacy images"])
            capture("migration-confirmation")
            tap(["轉換", "转换", "Convert"])
            if damaged {
                XCTAssertTrue(element(["CBZ 損壞", "CBZ 损坏"]).waitForExistence(timeout: 15), app.debugDescription)
                capture("damaged-cbz-retained")
                tap(["自動移除損壞的 CBZ", "自动移除损坏的 CBZ", "Automatically remove damaged CBZ files"])
                capture("damaged-cbz-opt-in")
                tap(["重試", "重试", "Retry"])
            }
            let migration = element(["轉換舊版圖片", "转换旧版图片", "Convert legacy images"])
            let gone = NSPredicate(format: "exists == false")
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: gone, object: migration)], timeout: 30), .completed)
        }
        tap(["QA Three Readers"])
        tap(["第 1 頁", "第 1 页", "Page 1"])
        XCTAssertTrue(element(["開啟下載內容", "打开下载内容", "Open downloaded content"]).waitForExistence(timeout: 10), app.debugDescription)
        // Inspect the chooser surface in sRGB. iOS appearance alone does not select a Yamibo palette.
        let surface = dark ? (42, 30, 24) : (255, 247, 224)
        let palette = NSPredicate { _, _ in
            self.largeColorArea { red, green, blue in
                abs(red - surface.0) < 8 && abs(green - surface.1) < 8 && abs(blue - surface.2) < 8
            }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: palette, object: app)], timeout: 10),
                       .completed, "Expected the selected Yamibo chooser palette")
        capture("download-reader-chooser")
        tap(labels)
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func assertFirstFloor() {
        let markers = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "READER_FLOOR_"))
        var visibleSince: Date?
        let settled = NSPredicate { _, _ in
            let first = markers.allElementsBoundByIndex.filter {
                $0.isHittable && $0.frame.intersects(self.app.frame)
            }.min { $0.frame.minY < $1.frame.minY }
            guard first?.label == "READER_FLOOR_1" else { visibleSince = nil; return false }
            if visibleSince == nil { visibleSince = Date() }
            return Date().timeIntervalSince(visibleSince!) >= 1
        }
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: settled, object: app)], timeout: 20)
        XCTAssertEqual(result, .completed, app.debugDescription)
    }

    private func checkNovel(dark: Bool) {
        openDownload(["小說閱讀", "小说阅读", "Novel reader"], dark: dark)
        assertFirstFloor()
        capture("novel-first-author-floor")
    }

    private func checkForum(dark: Bool) {
        openDownload(["論壇模式", "论坛模式", "Forum mode"], dark: dark, damaged: dark)
        assertFirstFloor()
        capture("forum-first-author-floor")
    }

    private func checkComic(dark: Bool) {
        openDownload(["智能漫畫", "智能漫画", "Smart Comic"], dark: dark, damaged: dark, migrateInManager: !dark)
        XCTAssertTrue(element(["1 / 3"]).waitForExistence(timeout: 20), app.debugDescription)
        waitForImage(second: false)
        capture("comic-first-painted-image")
        app.swipeLeft()
        XCTAssertTrue(element(["2 / 3"]).waitForExistence(timeout: 8), app.debugDescription)
        waitForImage(second: true)
        capture("comic-second-floor")
        if dark {
            app.swipeLeft()
            XCTAssertTrue(element(["3 / 3"]).waitForExistence(timeout: 10), app.debugDescription)
            XCTAssertTrue(element(["圖片載入失敗", "图片载入失败", "Image load failed"]).waitForExistence(timeout: 15), app.debugDescription)
            XCTAssertTrue(element(["這張離線圖片無法讀取", "这张离线图片无法读取", "This downloaded image could not be read"]).exists)
            capture("reader-damaged-archive")
            tap(["重新載入", "重新载入", "Reload"])
            XCTAssertTrue(element(["下載失敗", "下载失败", "Download failed"]).waitForExistence(timeout: 30), app.debugDescription)
            capture("reader-missing-image-retry")
            app.swipeRight()
            waitForImage(second: true)
        }
    }

    func testNovel() { checkNovel(dark: false) }
    func testNovelDark() { checkNovel(dark: true) }
    func testForum() { checkForum(dark: false) }
    func testForumDark() { checkForum(dark: true) }
    func testComic() { checkComic(dark: false) }
    func testComicDark() { checkComic(dark: true) }

    private enum Mode: String {
        case comic = "智能漫畫", novel = "小說閱讀", forum = "論壇模式"
    }

    private func openModePicker(from mode: Mode) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tap(mode == .comic ? ["📖"] : ["閱讀模式"])
        let selected = element(["已選擇"])
        XCTAssertTrue(selected.waitForExistence(timeout: 8), app.debugDescription)
        let row = element([mode.rawValue])
        XCTAssertTrue(row.exists, app.debugDescription)
        XCTAssertLessThan(abs(selected.frame.midY - row.frame.midY), 16, app.debugDescription)
    }

    // This fixture gives PID 1402 a unique magenta image. Aim the text reader's
    // viewport anchor at it before switching, without relying on scroll distance.
    private func atSecondImage() -> Bool {
        largeColorArea(centerOnly: true) { $0 >= 190 && $1 < 100 && $2 >= 140 }
    }

    private func assertSecondImage(in mode: Mode) {
        if mode == .comic {
            XCTAssertTrue(element(["2 / 3"]).waitForExistence(timeout: 12), app.debugDescription)
        }
        let marker = NSPredicate { _, _ in self.atSecondImage() }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: marker, object: app)], timeout: 15),
                       .completed, "Expected PID 1402 image at reading center in \(mode.rawValue): \(app.debugDescription)")
    }

    private func checkSwitch(from source: Mode, to target: Mode) {
        openDownload([source.rawValue], dark: false)
        if source == .comic {
            waitForImage(second: false)
            app.swipeLeft()
        } else {
            assertFirstFloor()
            for _ in 0..<12 {
                if atSecondImage() { break }
                let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.46, dy: 0.68))
                let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.46, dy: 0.47))
                start.press(forDuration: 0.12, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.18)
            }
        }
        assertSecondImage(in: source)
        capture("source-pid-1402")
        openModePicker(from: source)
        tap([target.rawValue])
        assertSecondImage(in: target)
        capture("destination-pid-1402")
        openModePicker(from: target)
        tap([source.rawValue])
        assertSecondImage(in: source)
        capture("return-pid-1402")
        XCUIDevice.shared.press(.home)
        openDownload([target.rawValue], dark: false, migrateInManager: false)
        assertSecondImage(in: target)
        capture("cold-reopen-pid-1402")
        openModePicker(from: target)
    }

    func testComicToNovel() { checkSwitch(from: .comic, to: .novel) }
    func testNovelToComic() { checkSwitch(from: .novel, to: .comic) }
    func testComicToForum() { checkSwitch(from: .comic, to: .forum) }
    func testForumToComic() { checkSwitch(from: .forum, to: .comic) }
    func testNovelToForum() { checkSwitch(from: .novel, to: .forum) }
    func testForumToNovel() { checkSwitch(from: .forum, to: .novel) }

    private func waitForImage(second: Bool) {
        let painted = NSPredicate { _, _ in
            self.largeColorArea { red, green, blue in
                second ? red >= 190 && green < 100 && blue >= 140
                       : red < 80 && green >= 140 && green <= 220 && blue >= 190
            }
        }
        expectation(for: painted, evaluatedWith: app)
        waitForExpectations(timeout: 15)
    }

    private func largeColorArea(centerOnly: Bool = false, matching: (Int, Int, Int) -> Bool) -> Bool {
        let source = app.screenshot().image.cgImage!
        let width = source.width, height = source.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(source, in: CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
        }
        if centerOnly {
            var hits = 0
            for y in -4...4 {
                for x in -4...4 {
                    let offset = ((height / 2 + y * height / 100) * width + width / 2 + x * width / 100) * 4
                    if matching(Int(pixels[offset]), Int(pixels[offset + 1]), Int(pixels[offset + 2])) { hits += 1 }
                }
            }
            return hits >= 42
        }
        var matchingPixels = 0
        var offset = 0
        while offset < pixels.count {
            let red = Int(pixels[offset])
            let green = Int(pixels[offset + 1])
            let blue = Int(pixels[offset + 2])
            if matching(red, green, blue) {
                matchingPixels += 1
            }
            offset += 4
        }
        return matchingPixels > 100_000
    }

}
