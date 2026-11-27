import UIKit
import QuartzCore

// QA-only. A small noninteractive strip lets the captured simulator video and UITouch
// share a monotonic timebase. It stays outside every measured content region.
final class ReaderFrameProbe: UIView {
    private static let probe = ReaderFrameProbe()
    private var link: CADisplayLink?
    private var stamp = 0
    private let stream = DispatchQueue(label: "reader.perf.events")
    private let filename = "reader-perf-events-\(UUID().uuidString).jsonl"

    static func install() {
        guard ProcessInfo.processInfo.arguments.contains("--reader-frame-probe") else { return }
        if ProcessInfo.processInfo.arguments.contains("--reader-fresh-replay") {
            #if targetEnvironment(simulator)
            precondition(Bundle.main.bundleIdentifier=="com.yamibo.bbs.readermodesqa")
            let fm=FileManager.default
            // This is a disposable simulator sandbox with synthetic data. Remove the previous
            // replay's disk cache/database before application initialization for every cold round.
            for scope in [FileManager.SearchPathDirectory.cachesDirectory,.applicationSupportDirectory,.libraryDirectory] {
                guard let base=fm.urls(for:scope,in:.userDomainMask).first else { continue }
                if scope == .cachesDirectory, let files=try? fm.contentsOfDirectory(at:base,includingPropertiesForKeys:nil) {
                    for path in files { try? fm.removeItem(at:path) }
                } else if let files=fm.enumerator(at:base,includingPropertiesForKeys:nil) {
                    var saved=[URL]()
                    for case let path as URL in files where ["yamibo.db","yamibo.db-shm","yamibo.db-wal"].contains(path.lastPathComponent) { saved.append(path) }
                    for path in saved { try? fm.removeItem(at:path) }
                }
            }
            URLCache.shared.removeAllCachedResponses()
            #endif
        }
        let original = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.sendEvent(_:)))!
        let replacement = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.readerPerfSend(_:)))!
        method_exchangeImplementations(original, replacement)
        let p = probe
        p.isUserInteractionEnabled = false
        p.isAccessibilityElement = false
        p.isOpaque = true
        p.layer.zPosition = 10000
        let link = CADisplayLink(target: p, selector: #selector(drawClock(_:)))
        link.add(to: .main, forMode: .common)
        p.link = link
    }
    static func record(_ event: UIEvent, window: UIWindow) {
        let p = probe
        if p.superview !== window && window.isKeyWindow {
            p.removeFromSuperview()
            p.frame = CGRect(x: 6, y: window.bounds.height - 22, width: 34 * 3, height: 8)
            p.autoresizingMask = [.flexibleTopMargin]
            window.addSubview(p)
        }
        for t in event.allTouches ?? [] where t.window === window && (t.phase == .began || t.phase == .ended || t.phase == .cancelled) {
            let point = t.location(in: window)
            let row: [String: Any] = ["time": t.timestamp, "phase": t.phase.rawValue, "x": point.x, "y": point.y,
                                      "wall": Date().timeIntervalSince1970, "uptime": ProcessInfo.processInfo.systemUptime,
                                      "width": window.bounds.width, "height": window.bounds.height,
                                      "scale": window.screen.scale, "pid": ProcessInfo.processInfo.processIdentifier]
            p.stream.async {
                let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let url = dir.appendingPathComponent(p.filename)
                if !FileManager.default.fileExists(atPath: url.path) {
                    FileManager.default.createFile(atPath: url.path, contents: nil)
                }
                guard let data = try? JSONSerialization.data(withJSONObject: row),
                      let f = try? FileHandle(forWritingTo: url) else { return }
                defer { try? f.close() }
                f.seekToEndOfFile(); f.write(data); f.write(Data([10]))
            }
        }
    }
    @objc private func drawClock(_ d: CADisplayLink) {
        stamp = Int((d.targetTimestamp * 1000).rounded()) & 0xffffff
        setNeedsDisplay()
    }
    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        c.setFillColor(UIColor.black.cgColor); c.fill(bounds)
        for i in 0..<34 {
            let color: UIColor
            switch i {
            case 0, 1: color = .red
            case 2, 3: color = .green
            case 4, 5: color = .blue
            case 6..<30: color = ((stamp >> (i-6)) & 1) == 1 ? .white : .black
            case 30, 32: color = .white
            default: color = .black
            }
            c.setFillColor(color.cgColor); c.fill(CGRect(x: i*3, y: 0, width: 3, height: 8))
        }
    }
}
private extension UIWindow {
    @objc func readerPerfSend(_ event: UIEvent) {
        ReaderFrameProbe.record(event, window: self)
        readerPerfSend(event)
    }
}
