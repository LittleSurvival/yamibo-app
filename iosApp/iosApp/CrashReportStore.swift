import ComposeApp
import CryptoKit
import Foundation
import MetricKit

final class CrashReportStore: NSObject, MXMetricManagerSubscriber {
    static let shared = CrashReportStore()

    struct Report: Identifiable, Codable {
        let id: String
        let date: Date
        let title: String
        let metadata: String
        let text: String
    }

    private let directory = URL.applicationSupportDirectory.appendingPathComponent("CrashReports", isDirectory: true)
    private let metricQueue = DispatchQueue(label: "me.thenano.yamibo.crash-metrics")
    private var installed = false

    // App initialization on the main thread.
    func install() {
        guard !installed else { return }
        installed = true
        IOSCrashReportKt.installIOSCrashHook { title, stack in
            self.recordKotlin(title: title, stack: stack)
        }
        MXMetricManager.shared.add(self)
    }

    func pendingReport() -> Report? {
        let reports = ["kotlin.json", "metric.json"].compactMap { readReport($0) }
        guard let latest = reports.max(by: { $0.date < $1.date }) else { return nil }
        let viewed = try? Data(contentsOf: directory.appendingPathComponent("viewed.json"))
        if let viewed, (try? JSONDecoder().decode(String.self, from: viewed)) == latest.id { return nil }
        return latest
    }

    func markViewed(_ report: Report) throws {
        try write(JSONEncoder().encode(report.id), name: "viewed.json")
    }

    private func readReport(_ name: String) -> Report? {
        let url = directory.appendingPathComponent(name)
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              size <= 512 * 1024, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Report.self, from: data)
    }

    private func write(_ data: Data, name: String) throws {
        var folder = directory
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var attributes = URLResourceValues()
        attributes.isExcludedFromBackup = true
        try folder.setResourceValues(attributes)
        try data.write(to: folder.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func recordKotlin(title: String, stack: String) {
        let now = Date()
        let id = UUID().uuidString
        let bundle = Bundle.main
        let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        let app = "\(version) (\(build))"
        let process = ProcessInfo.processInfo
        let os = process.operatingSystemVersion
        let text = """
        Crash ID: \(id)
        Source: Kotlin/Native
        Time: \(now.ISO8601Format())
        App: \(app)
        iOS: \(os.majorVersion).\(os.minorVersion).\(os.patchVersion)
        PID: \(process.processIdentifier)
        Thread: \(Thread.isMainThread ? "main" : "background")

        \(stack)
        """
        let report = Report(id: id, date: now, title: title, metadata: "\(now.formatted()) · \(app)", text: bounded(text))
        try? write(JSONEncoder().encode(report), name: "kotlin.json")
    }

    // iOS can deliver historical payloads, out of order or more than once. Compare reporting
    // periods rather than delivery dates, and retain a stable ID for the same diagnosis.
    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        metricQueue.async {
            var latest = self.readReport("metric.json")
            for payload in payloads {
                guard let crashes = payload.crashDiagnostics, !crashes.isEmpty else { continue }
                if let latest, payload.timeStampEnd < latest.date { continue }
                for crash in crashes {
                    let report = self.metricReport(crash, payload: payload)
                    if let latest, report.date == latest.date, report.id <= latest.id { continue }
                    do {
                        try self.write(JSONEncoder().encode(report), name: "metric.json")
                        latest = report
                    } catch {
                        continue
                    }
                }
            }
        }
    }

    private func metricReport(_ crash: MXCrashDiagnostic, payload: MXDiagnosticPayload) -> Report {
        let begin = payload.timeStampBegin.ISO8601Format()
        let end = payload.timeStampEnd.ISO8601Format()
        let version = "\(crash.applicationVersion) (\(crash.metaData.applicationBuildVersion))"
        let signal = crash.signal?.stringValue ?? "?"
        let body = """
        Source: Apple MetricKit
        Reporting period: \(begin) – \(end)
        App: \(version)
        OS: \(crash.metaData.osVersion)
        Signal: \(signal)
        Exception type: \(crash.exceptionType?.stringValue ?? "?")
        Exception code: \(crash.exceptionCode?.stringValue ?? "?")

        \(stackSummary(crash.callStackTree))
        """
        let id = SHA256.hash(data: Data(body.utf8)).map { String(format: "%02x", $0) }.joined()
        let metadata = "報告期間截至 \(payload.timeStampEnd.formatted()) · \(version)"
        return Report(id: id, date: payload.timeStampEnd, title: "MetricKit · Signal \(signal)",
                      metadata: metadata, text: bounded("Crash ID: \(id)\n\(body)"))
    }

    // Keep symbolication inputs from the attributed thread, without persisting raw diagnostics,
    // Objective-C exception messages, absolute paths, or VM region descriptions.
    private func stackSummary(_ tree: MXCallStackTree) -> String {
        typealias Fields = [String: Any]
        let data = tree.jsonRepresentation()
        guard data.count <= 2 * 1024 * 1024,
              let root = (try? JSONSerialization.jsonObject(with: data)) as? Fields,
              let stacks = ((root["callStackTree"] as? Fields) ?? root)["callStacks"] as? [Fields],
              let stack = stacks.first(where: { $0["threadAttributed"] as? Bool == true }) ?? stacks.first,
              let roots = stack["callStackRootFrames"] as? [Fields], !roots.isEmpty else { return "Stack unavailable." }
        var pending = Array(roots.prefix(100).reversed())
        let attributed = stack["threadAttributed"] as? Bool == true
        var lines = [attributed ? "Attributed thread:" : "First reported thread:"]
        while lines.count <= 100, let frame = pending.popLast() {
            let name = ((frame["binaryName"] as? String ?? "?") as NSString).lastPathComponent
            let uuid = UUID(uuidString: frame["binaryUUID"] as? String ?? "")?.uuidString ?? "?"
            let offset = (frame["offsetIntoBinaryTextSegment"] as? NSNumber)?.stringValue ?? "?"
            lines.append("\(name.prefix(100)) [\(uuid)] + \(offset)")
            if let children = frame["subFrames"] as? [Fields] {
                pending.append(contentsOf: children.prefix(100 - (lines.count - 1)).reversed())
            }
        }
        return lines.joined(separator: "\n")
    }

    private func bounded(_ text: String) -> String {
        let limit = 64 * 1024
        guard text.utf8.count > limit else { return text }
        let marker = "\n[Report truncated]"
        let prefix = text.utf8.prefix(limit - marker.utf8.count - 3)
        return String(decoding: prefix, as: UTF8.self) + marker
    }
}
