import XCTest
import UIKit

final class ReaderModesUITests: XCTestCase {
    private let guardLocalTransfers = false // READER_PERF_GUARD
    private let promptInput = false // READER_PERF_PROMPT
    private let benchmarkNetwork = "none" // READER_PERF_NETWORK
    private let benchmarkChapters = 0 // READER_PERF_CHAPTERS
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
            if benchmarkNetwork != "none" || benchmarkChapters>0 {
                let signPrompt=app.staticTexts["你今天還沒簽到"]
                if signPrompt.exists {
                    let suppress=element(["今日不再提醒"])
                    if suppress.exists && suppress.isHittable { suppress.tap() }
                    let dismiss=element(["取消"])
                    if dismiss.exists && dismiss.isHittable { dismiss.tap() }
                }
            }
            let later = app.buttons.matching(NSPredicate(format: "label IN %@", ["稍後", "稍后", "Later"])).firstMatch
            if later.exists && later.isHittable { later.tap() }
            let target = element(labels)
            if target.waitForExistence(timeout: 1), target.isHittable { target.tap(); return }
        }
        while Date() < deadline
        XCTFail("Missing control " + labels.joined(separator: ", ") + " " + app.debugDescription)
    }

    private var measureNavigation: ((String,Double,Double)->Void)?
    private func tapDownload(_ labels:[String],stage:String) {
        let start=ProcessInfo.processInfo.systemUptime
        tap(labels)
        measureNavigation?(stage,start,ProcessInfo.processInfo.systemUptime)
    }

    private func openDownload(_ labels: [String], dark: Bool, damaged: Bool = false, migrateInManager: Bool = true, groupTitle: String = "QA Three Readers", beforeOpen: (() -> Void)? = nil) {
        continueAfterFailure = false
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(zh-Hant)", "-AppleLocale", "zh_TW"]
        // SettingsRegistry stores delegated property names in lowercase.
        if dark { app.launchArguments += ["-appsettings.themescheme", "DEFAULT_DARK"] }
        if beforeOpen != nil { app.launchArguments += ["--reader-frame-probe"] }
        if benchmarkChapters > 0 { app.launchArguments += ["-uid","7","-username","QA Bench","-userGroup","Fixture"] }
        app.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let alert = springboard.alerts.firstMatch
        if alert.waitForExistence(timeout: 3) { alert.buttons.firstMatch.tap() }
        tap(["我的", "Profile"])
        let contentLabels = ["下載內容管理", "下载内容管理", "Downloaded content", "內容管理"]
        let manager = app.buttons.matching(NSPredicate(format: "label IN %@",
            ["下載管理, 下載管理", "下載管理", "下载管理, 下载管理", "下载管理", "Download manager", "Downloads"])).firstMatch
        // The Profile row contains a separately accessible image with the same
        // label. Confirm navigation before touching the next screen.
        for _ in 0..<3 {
            if element(contentLabels).exists { break }
            let later = app.buttons["稍後"]
            if later.exists && later.isHittable { later.tap() }
            if manager.waitForExistence(timeout: 2) && manager.isHittable { manager.tap() }
            else if benchmarkChapters>0 { app.swipeUp() }
            if element(contentLabels).waitForExistence(timeout: 5) { break }
        }
        tap(contentLabels)
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
        tapDownload([groupTitle],stage:"download_group")
        tapDownload(["第 1 頁", "第 1 页", "Page 1"],stage:"download_page")
        XCTAssertTrue(element(["開啟下載內容", "打开下载内容", "Open downloaded content"]).waitForExistence(timeout: 10), app.debugDescription)
        if benchmarkChapters > 0 {
            XCTAssertTrue(element(["繼續這部作品"]).waitForExistence(timeout: 10), "Must find the alternate chapter belonging to seeded UID 7")
        }
        if let beforeOpen { beforeOpen(); return }
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

    private func checkRepair(dark: Bool) {
        openDownload(["智能漫畫", "智能漫画", "Smart Comic"], dark: dark, damaged: true)
        waitForImage(second: false)
        app.swipeLeft()
        waitForImage(second: true)
        app.swipeLeft()
        XCTAssertTrue(element(["3 / 3"]).waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(element(["圖片載入失敗", "图片载入失败"]).waitForExistence(timeout: 15), app.debugDescription)
        capture("missing-image-before-user-reload")

        let ready = expectation(description: "Fixture server enabled after missing image is visible")
        var request = URLRequest(url: URL(string: "https://127.0.0.1:18083/arm")!)
        request.httpMethod = "POST"
        URLSession.shared.dataTask(with: request) { _, response, error in
            XCTAssertNil(error)
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 204)
            ready.fulfill()
        }.resume()
        wait(for: [ready], timeout: 12)
        tap(["重新載入", "重新载入", "Reload"])
        waitForRepairedImage()
        capture("repaired-third-image")
        app.swipeRight()
        waitForImage(second: true)
        app.swipeLeft()
        waitForRepairedImage()
    }

    private func waitForRepairedImage() {
        let painted = NSPredicate { _, _ in
            self.largeColorArea { red, green, blue in red < 80 && green > 145 && blue < 80 }
        }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: painted, object: app)], timeout: 20),
                       .completed, app.debugDescription)
    }

    func testRepair() { checkRepair(dark: false) }
    func testRepairDark() { checkRepair(dark: true) }

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

    private func performance(_ mode: Mode) {
        // The runner provides this variable in the generated test source, not in the App.
        let rounds = 1 // READER_PERF_ROUNDS
        let cold = true // READER_PERF_COLD
        var rows = [[String: Any]]()
        func record(_ key: String, round: Int, action: () -> Void) {
            let before = ProcessInfo.processInfo.systemUptime
            action()
            let row: [String: Any] = ["round": round, "action": key, "lo": before, "hi": ProcessInfo.processInfo.systemUptime]
            rows.append(row)
            if let data = try? JSONSerialization.data(withJSONObject: [row]) {
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
                attachment.name = "input-\(round)-\(key)"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        defer {
            if let data = try? JSONSerialization.data(withJSONObject: rows) {
                let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
                attachment.name = "reader-perf-actions"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
        let first = cold ? 1 : 0
        defer { measureNavigation=nil }
        for round in first...rounds {
            measureNavigation={ key,lo,hi in rows.append(["action":key,"round":round,"lo":lo,"hi":hi]) }
            let select = {
                let button = self.element([mode.rawValue])
                XCTAssertTrue(button.exists)
                record("open", round: round) { button.tap() }
            }
            if guardLocalTransfers || benchmarkNetwork != "none" {
                let runKey="ios-\(round)-\(mode == .comic ? "comic" : (mode == .novel ? "novel":"forum"))-\(benchmarkNetwork)"
                let caseURL=URL(string:"http://127.0.0.1:18928/__case/"+runKey)!
                var probes=[(low: Double,high: Double,host: Double)]()
                for _ in 0..<4 {
                    let ack=expectation(description:"loopback replay armed")
                    var hostNanoseconds: Double?
                    let lo=ProcessInfo.processInfo.systemUptime
                    var received: Double?
                    URLSession.shared.dataTask(with:caseURL) { _,resp,error in
                        received=ProcessInfo.processInfo.systemUptime
                        XCTAssertNil(error)
                        let reply=resp as? HTTPURLResponse
                        XCTAssertEqual(reply?.statusCode,204)
                        hostNanoseconds=reply?.value(forHTTPHeaderField:"X-Fixture-Monotonic-Ns").flatMap(Double.init)
                        ack.fulfill()
                    }.resume()
                    wait(for:[ack],timeout:8)
                    if let hostNanoseconds,let received { probes.append((lo,received,hostNanoseconds)) }
                }
                guard let fastest=probes.min(by:{ $0.high-$0.low < $1.high-$1.low }) else { XCTFail("No origin clock reply"); return }
                rows.append(["action":"origin","round":round,"case":runKey,"lo":fastest.low,"hi":fastest.high,"host_ns":fastest.host,
                             "clock_probe_ms":probes.map { ($0.high-$0.low)*1000 }])
            }
            if benchmarkNetwork != "none" {
                continueAfterFailure = false
                app.terminate()
                let setting = mode == .forum ? "Forum" : (mode == .novel ? "Novel" : "SmartComic")
                app.launchArguments = ["-AppleLanguages","(zh-Hant)","-AppleLocale","zh_TW","--reader-frame-probe","--reader-fresh-replay",
                                       "-appsettings.forumReadingMode.30",setting, "-uid","7","-username","QA Author","-userGroup","QA",
                                       "-cookie","EeqY_2132_auth=synthetic; EeqY_2132_saltkey=synthetic"]
                app.launch()
                let alert=XCUIApplication(bundleIdentifier:"com.apple.springboard").alerts.firstMatch
                if alert.waitForExistence(timeout:1) { alert.buttons.firstMatch.tap() }
                tap(["QA Forum"])
                if mode == .novel {
                    record("board",round:round) { tap(["QA Three Readers"]) }
                    record("open",round:round) { tap(["開始閱讀","繼續閱讀"]) }
                } else {
                    record("open",round:round) { tap(["QA Three Readers"]) }
                }
            } else if cold || round == 0 {
                openDownload([mode.rawValue], dark: false, migrateInManager: false, beforeOpen: select)
            } else {
                tapDownload(["第 1 頁", "Page 1"],stage:"download_page")
                select()
            }
            // Seek a complete first-floor witness. Elapsed XCTest time is never used for T1.
            func verifyFirstContent() {
                waitForImage(second: false)
                if mode != .comic {
                    assertFirstFloor()
                    let node=app.staticTexts.matching(NSPredicate(format:"label == %@", "READER_FLOOR_1")).firstMatch
                    let bbox=node.frame, t=ProcessInfo.processInfo.systemUptime
                    rows.append(["round":round,"action":"glyph","lo":t,"hi":t,"label":node.label,
                                 "rect":[bbox.minX,bbox.minY,bbox.maxX,bbox.maxY]])
                }
            }
            if !promptInput { verifyFirstContent() }
            record("gesture", round: round) {
                if mode == .comic { self.app.swipeLeft() } else { self.app.swipeUp() }
            }
            if mode == .comic { waitForImage(second: true) }
            if mode == .comic { app.swipeRight(); waitForImage(second: false) }
            else {
                // Reverse the unmeasured setup motion until the first full label is on screen.
                // A single reverse fling has variable distance; preserve the original response window.
                for _ in 0..<4 {
                    app.swipeDown()
                    let first=app.staticTexts["READER_FLOOR_1"]
                    if first.exists && app.frame.contains(first.frame) && first.isHittable { break }
                }
                assertFirstFloor()
            }
            if promptInput {
                verifyFirstContent()
                app.coordinate(withNormalizedOffset: CGVector(dx:0.5,dy:0.5)).tap()
                let control=element(["設定"])
                XCTAssertTrue(control.exists)
                record("settings",round:round) { control.tap() }
                XCTAssertTrue(element(mode == .comic ? ["輕觸區域"] : ["文字排版"]).waitForExistence(timeout:8),app.debugDescription)
                if round==rounds { capture("settings-visible-after-action") }
            }
            if benchmarkChapters>0 && round==rounds {
                app.coordinate(withNormalizedOffset: CGVector(dx:0.5,dy:0.5)).tap()
                tap(["☰"])
                XCTAssertTrue(element(["QA CHAPTER 0005"]).waitForExistence(timeout:10),app.debugDescription)
                tap(["升冪"])
                XCTAssertTrue(element(["降冪"]).waitForExistence(timeout:10),app.debugDescription)
                XCTAssertTrue(element(["QA CHAPTER 0003"]).waitForExistence(timeout:10),app.debugDescription)
                capture("uid7-reversed-catalog")
            }
            if !cold {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                tap(["◀"])
                // The download manager restores either the group or its page list.
                if !element(["第 1 頁", "Page 1"]).exists { tap(["QA Three Readers"]) }
            }
        }
    }

    func testSustainedOpenAndReturn() {
        continueAfterFailure=false
        var rows=[[String:Any]](), previous=""
        func mark(_ round:Int,_ action:String,_ f:()->Void) {
            let before=ProcessInfo.processInfo.systemUptime
            f()
            rows.append(["round":round,"action":action,"lo":before,"hi":ProcessInfo.processInfo.systemUptime])
        }
        defer {
            if let data=try? JSONSerialization.data(withJSONObject:rows) {
                let a=XCTAttachment(data:data,uniformTypeIdentifier:"public.json");a.name="reader-perf-actions";a.lifetime = .keepAlways;add(a)
            }
        }
        for round in 1...30 {
            let comic=round%2==1, mode=round%2==1 ? Mode.comic:Mode.novel
            let title=comic ? "QA Three Readers":"QA Text Only"
            let open={ mark(round,"open") { self.tap([mode.rawValue]) } }
            if round==1 {
                openDownload([mode.rawValue],dark:false,migrateInManager:false,groupTitle:title,beforeOpen:open)
            } else {
                if element(["第 1 頁","Page 1"]).exists { tap([previous]) }
                tap([title]);tap(["第 1 頁","Page 1"]);open()
            }
            if comic { waitForImage(second:false) }
            else {
                let text=app.staticTexts["READER_TEXT_1"]
                XCTAssertTrue(text.waitForExistence(timeout:15) && text.isHittable,app.debugDescription)
                let rect=text.frame, now=ProcessInfo.processInfo.systemUptime
                rows.append(["round":round,"action":"glyph","label":text.label,"lo":now,"hi":now,
                             "rect":[rect.minX,rect.minY,rect.maxX,rect.maxY]])
            }
            mark(round,"correct") { if [1,10,30].contains(round) { capture("correct-reader-\(round)") } }
            app.coordinate(withNormalizedOffset:CGVector(dx:0.5,dy:0.5)).tap()
            mark(round,"back") { tap(["◀"]) }
            XCTAssertTrue(element([title]).waitForExistence(timeout:12),app.debugDescription)
            mark(round,"idle") { Thread.sleep(forTimeInterval:1) }
            previous=title
        }
    }

    func testNativeLongFirstImage() {
        var rows = [[String: Any]]()
        func mark(_ action: String, operation: () -> Void) {
            let before = ProcessInfo.processInfo.systemUptime
            operation()
            rows.append(["round": 1, "action": action, "lo": before, "hi": ProcessInfo.processInfo.systemUptime])
        }
        defer {
            if let data = try? JSONSerialization.data(withJSONObject: rows) {
                let result = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
                result.name = "reader-perf-actions"
                result.lifetime = .keepAlways
                add(result)
            }
        }
        openDownload([Mode.comic.rawValue], dark: false, migrateInManager: false, groupTitle: "Storage fixture") {
            mark("open") { self.tap([Mode.comic.rawValue]) }
        }
        XCTAssertTrue(element(["1 / 20"]).waitForExistence(timeout: 15), app.debugDescription)
        // Keep the bitmap on screen for independent comparison with the real native blob.
        mark("visible") {
            capture("native-long-first-image")
            Thread.sleep(forTimeInterval: 2)
        }
    }

    func testPerfComic() { performance(.comic) }
    func testPerfNovel() { performance(.novel) }
    func testPerfForum() { performance(.forum) }

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
