import SwiftUI
import BackgroundTasks
import ComposeApp

@main
struct iOSApp: App {
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
    }
}
