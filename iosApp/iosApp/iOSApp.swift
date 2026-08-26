import SwiftUI
import BackgroundTasks
import ComposeApp
import UIKit
import UserNotifications

private final class MessageNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        switch response.actionIdentifier {
        case IOSMessageNotificationBackgroundKt.MESSAGE_NOTIFICATION_MUTE_ACTION_IDENTIFIER:
            IOSMessageNotificationBackgroundKt.muteMessageNotificationsToday { _ in
                completionHandler()
            }
        case IOSMessageNotificationBackgroundKt.MESSAGE_NOTIFICATION_OPEN_ACTION_IDENTIFIER,
             UNNotificationDefaultActionIdentifier:
            IOSMessageNotificationBackgroundKt.openMessageCenterFromIOSNotification()
            completionHandler()
        default:
            completionHandler()
        }
    }
}

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase
    private let messageNotificationDelegate = MessageNotificationDelegate()

    init() {
        let center = UNUserNotificationCenter.current()
        center.delegate = messageNotificationDelegate
        let open = UNNotificationAction(
            identifier: IOSMessageNotificationBackgroundKt.MESSAGE_NOTIFICATION_OPEN_ACTION_IDENTIFIER,
            title: "查看通知",
            options: [.foreground]
        )
        let mute = UNNotificationAction(
            identifier: IOSMessageNotificationBackgroundKt.MESSAGE_NOTIFICATION_MUTE_ACTION_IDENTIFIER,
            title: "不再提醒（僅限今日）",
            options: []
        )
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: IOSMessageNotificationBackgroundKt.MESSAGE_NOTIFICATION_CATEGORY_IDENTIFIER,
                actions: [open, mute],
                intentIdentifiers: [],
                options: []
            )
        ])
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
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: IOSMessageNotificationBackgroundKt.MESSAGE_NOTIFICATION_BACKGROUND_TASK_IDENTIFIER,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            var completed = false
            refreshTask.expirationHandler = {
                guard !completed else { return }
                completed = true
                IOSMessageNotificationBackgroundKt.cancelMessageNotificationBackground()
                refreshTask.setTaskCompleted(success: false)
            }
            IOSMessageNotificationBackgroundKt.runMessageNotificationBackground { success in
                guard !completed else { return }
                completed = true
                refreshTask.setTaskCompleted(success: success.boolValue)
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
