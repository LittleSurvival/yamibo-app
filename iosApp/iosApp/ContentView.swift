import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ReaderHostController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// UIKit owns the system gestures; Compose owns the current screen and its scroll position.
private final class ReaderHostController: UIViewController, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    private let compose = MainViewControllerKt.MainViewController()
    private let statusBarTarget = UIScrollView()
    private final class SuspendedScrollView {
        weak var view: UIScrollView?
        init(_ view: UIScrollView) { self.view = view }
    }
    private var suspendedScrollViews: [SuspendedScrollView] = []
    private var hostIsVisible = false

    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(compose)
        view.addSubview(compose.view)
        compose.didMove(toParent: self)

        // UIKit requires one eligible on-screen scroll view. Keep the bridge behind
        // Compose so it cannot intercept content touches, with a nonzero scroll range.
        statusBarTarget.contentInsetAdjustmentBehavior = .never
        statusBarTarget.delegate = self
        statusBarTarget.isAccessibilityElement = false
        view.insertSubview(statusBarTarget, belowSubview: compose.view)
        // Leave UIKit/WebView scrolling alone when the active screen has no Compose handler.
        NativeNavigationBridge.shared.onScrollHandlersChanged = { [weak self] in
            self?.updateScrollToTopOwnership()
        }

        let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(edgeBack(_:)))
        edge.edges = .left
        edge.delegate = self
        view.addGestureRecognizer(edge)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        compose.view.frame = view.bounds
        statusBarTarget.frame = view.bounds
        statusBarTarget.contentSize = CGSize(width: view.bounds.width, height: view.bounds.height + 1)
        statusBarTarget.contentOffset = CGPoint(x: 0, y: 1)
        updateScrollToTopOwnership()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        hostIsVisible = true
        updateScrollToTopOwnership()
    }

    override func viewWillDisappear(_ animated: Bool) {
        hostIsVisible = false
        updateScrollToTopOwnership()
        super.viewWillDisappear(animated)
    }

    private func updateScrollToTopOwnership() {
        let ownsScrolling = hostIsVisible && presentedViewController == nil &&
            compose.presentedViewController == nil && NativeNavigationBridge.shared.hasScrollToTopHandler()
        statusBarTarget.scrollsToTop = ownsScrolling
        if ownsScrolling {
            // Scope changes to this host's Compose subtree, and restore native
            // participants when a WebView or another native screen takes ownership.
            func suspendParticipants(in root: UIView) {
                if let scroll = root as? UIScrollView, scroll.scrollsToTop {
                    suspendedScrollViews.append(SuspendedScrollView(scroll))
                    scroll.scrollsToTop = false
                }
                root.subviews.forEach { suspendParticipants(in: $0) }
            }
            suspendedScrollViews.removeAll { $0.view == nil }
            suspendParticipants(in: compose.view)
        } else {
            suspendedScrollViews.forEach { $0.view?.scrollsToTop = true }
            suspendedScrollViews.removeAll()
        }
    }

    func scrollViewShouldScrollToTop(_ scrollView: UIScrollView) -> Bool {
        guard hostIsVisible, presentedViewController == nil,
              compose.presentedViewController == nil else { return false }
        NativeNavigationBridge.shared.scrollToTop()
        return false
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard presentedViewController == nil,
              compose.presentedViewController == nil,
              NativeNavigationBridge.shared.canNavigateBack(),
              let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
        let velocity = pan.velocity(in: view)
        return velocity.x > 0 && velocity.x > abs(velocity.y)
    }

    @objc private func edgeBack(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard gesture.state == .ended else { return }
        let translation = gesture.translation(in: view)
        let velocity = gesture.velocity(in: view)
        if translation.x > 64 || (translation.x > 16 && velocity.x > 600) {
            NativeNavigationBridge.shared.navigateBack()
        }
    }
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}



