import SwiftUI
import BackgroundTasks
import ComposeApp
import UIKit

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: IOSAppSyncBackgroundKt.APP_SYNC_BACKGROUND_TASK_IDENTIFIER,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            var completed = false
            processingTask.expirationHandler = {
                guard !completed else { return }
                completed = true
                IOSAppSyncBackgroundKt.cancelAppSyncBackground()
                processingTask.setTaskCompleted(success: false)
            }
            IOSAppSyncBackgroundKt.runAppSyncBackground { success in
                guard !completed else { return }
                completed = true
                processingTask.setTaskCompleted(success: success.boolValue)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                IOSAppSyncBackgroundKt.appSyncSceneDidBecomeActive()
            case .background:
                runForegroundExitSync()
            case .inactive:
                break
            @unknown default:
                break
            }
        }
    }

    private func runForegroundExitSync() {
        var taskIdentifier: UIBackgroundTaskIdentifier = .invalid
        var completed = false
        let finish = {
            guard !completed else { return }
            completed = true
            if taskIdentifier != .invalid {
                UIApplication.shared.endBackgroundTask(taskIdentifier)
                taskIdentifier = .invalid
            }
        }
        taskIdentifier = UIApplication.shared.beginBackgroundTask {
            IOSAppSyncBackgroundKt.cancelAppSyncBackground()
            finish()
        }
        IOSAppSyncBackgroundKt.appSyncSceneDidEnterBackground { _ in
            finish()
        }
    }
}
