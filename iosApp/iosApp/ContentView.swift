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

    override var childForStatusBarStyle: UIViewController? { compose }
    override var childForStatusBarHidden: UIViewController? { compose }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(compose)
        view.addSubview(compose.view)
        compose.didMove(toParent: self)

        // A visible one-point scroll view receives UIKit's status-bar tap callback.
        // Returning false from the delegate forwards the action to the active Compose list.
        statusBarTarget.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        statusBarTarget.contentSize = CGSize(width: 1, height: 2)
        statusBarTarget.contentOffset = CGPoint(x: 0, y: 1)
        statusBarTarget.contentInsetAdjustmentBehavior = .never
        statusBarTarget.scrollsToTop = NativeNavigationBridge.shared.hasScrollToTopHandler()
        statusBarTarget.delegate = self
        view.addSubview(statusBarTarget)
        // Leave UIKit/WebView scrolling alone when the active screen has no Compose handler.
        NativeNavigationBridge.shared.onScrollHandlersChanged = { [weak self] in
            self?.statusBarTarget.scrollsToTop = NativeNavigationBridge.shared.hasScrollToTopHandler()
        }

        let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(edgeBack(_:)))
        edge.edges = .left
        edge.delegate = self
        view.addGestureRecognizer(edge)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        compose.view.frame = view.bounds
    }

    func scrollViewShouldScrollToTop(_ scrollView: UIScrollView) -> Bool {
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



