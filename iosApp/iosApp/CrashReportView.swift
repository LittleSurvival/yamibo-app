import ComposeApp
import SwiftUI
import UIKit

struct CrashReportView: View {
    let report: CrashReportStore.Report
    let dismiss: () throws -> Void

    private let palette = IOSCrashReportPalette()
    @State private var expanded = false
    @State private var copied = false
    @State private var dismissError: String?

    var body: some View {
        ZStack {
            Color(argb: palette.background).overlay(Color.black.opacity(0.33)).ignoresSafeArea()
            ViewThatFits(in: .vertical) {
                card.fixedSize(horizontal: false, vertical: true)
                ScrollView { card }
            }
            .padding(18)
        }
        .preferredColorScheme(palette.dark ? .dark : .light)
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("App 意外關閉")
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("crashTitle")
            Text("上次使用 Yamibo 時，App 意外關閉。你可以複製日誌提供給開發者。")
                .font(.callout)
                .padding(.top, 8)
            Text(verbatim: report.title)
                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                .padding(.top, 15)
            Text(verbatim: report.metadata)
                .font(.footnote)
                .padding(.top, 3)
            Rectangle().fill(Color(argb: palette.line)).frame(height: 1).padding(.top, 13)
            Button {
                expanded.toggle()
            } label: {
                HStack {
                    Text(expanded ? "收起日誌" : "查看日誌")
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .accessibilityHidden(true)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .foregroundStyle(Color(argb: palette.action))
            .accessibilityIdentifier("crashDisclosure")
            if expanded {
                ScrollView {
                    Text(verbatim: report.text)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                        .accessibilityIdentifier("crashDetails")
                }
                .frame(height: 178)
                .background(Color(argb: palette.background), in: RoundedRectangle(cornerRadius: 6))
                .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Color(argb: palette.line)))
                .padding(.bottom, 12)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 4) { actions }.fixedSize(horizontal: true, vertical: false)
                VStack(alignment: .trailing, spacing: 4) { actions }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            if copied {
                Text("已複製日誌")
                    .font(.footnote)
                    .padding(.top, 8)
                    .accessibilityIdentifier("crashCopyFeedback")
            }
            if let dismissError {
                Text(verbatim: dismissError)
                    .font(.footnote)
                    .padding(.top, 8)
                    .accessibilityIdentifier("crashDismissError")
            }
        }
        .buttonStyle(.plain)
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(Color(argb: palette.foreground))
        .padding(22)
        .frame(maxWidth: 370)
        .background(Color(argb: palette.surface), in: RoundedRectangle(cornerRadius: 16))
        .shadow(color: .black.opacity(0.13), radius: 15, y: 6)
    }

    @ViewBuilder private var actions: some View {
        Button("上傳給開發者") {}
            .disabled(true)
            .foregroundStyle(Color(argb: palette.action).opacity(0.38))
            .padding(.horizontal, 6)
            .accessibilityIdentifier("crashUpload")
        Button {
            UIPasteboard.general.string = report.text
            copied = true
            UIAccessibility.post(notification: .announcement, argument: "已複製日誌")
        } label: {
            Text("複製日誌").padding(.horizontal, 7).frame(minHeight: 44)
        }
        .foregroundStyle(Color(argb: palette.action))
        .accessibilityIdentifier("crashCopy")
        Button {
            do {
                try dismiss()
            } catch {
                dismissError = "無法將日誌標記為已讀。請再按一次關閉以重試。\n\(error.localizedDescription)"
            }
        } label: {
            Text("關閉")
                .foregroundStyle(Color(argb: palette.onDeep))
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(Color(argb: palette.deep), in: RoundedRectangle(cornerRadius: 8))
                .overlay {
                    if palette.dark {
                        RoundedRectangle(cornerRadius: 8).strokeBorder(Color(argb: palette.line))
                    }
                }
        }
        .accessibilityIdentifier("crashClose")
    }
}

private extension Color {
    init(argb: Int32) {
        let bits = UInt32(bitPattern: argb)
        self.init(red: Double((bits >> 16) & 255) / 255,
                  green: Double((bits >> 8) & 255) / 255,
                  blue: Double(bits & 255) / 255,
                  opacity: Double(bits >> 24) / 255)
    }
}
