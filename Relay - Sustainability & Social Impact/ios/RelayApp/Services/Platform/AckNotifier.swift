import Foundation
@preconcurrency import UserNotifications
import RelayCore
import RelayKit

/// Local notification when the control room answers one of my requests while the app is not on screen (iOS shows
/// nothing for a notification posted while the app is in the foreground, which is what we want: the timeline on
/// screen already shows it). Android has no equivalent beyond its foreground-service notice; on iOS, where the app
/// is often suspended, this is how a villager learns "help is on the way".
///
/// Copy: a default English text lives here; `AppModel.ackNotificationText` lets the i18n layer replace it. The text
/// sent by the control room (`ackText`) is shown when present, since the operator wrote it in the right language.
struct AckNotifier {
    typealias TextProvider = @MainActor (_ need: MyNeed, _ status: AckStatus) -> (title: String, body: String)

    static func defaultText(need: MyNeed, status: AckStatus) -> (title: String, body: String) {
        let fallback: String
        switch status {
        case .received: fallback = "Your request reached the control room."
        case .dispatched: fallback = "Help has been assigned to your request."
        case .onTheWay: fallback = "Help is on the way."
        case .resolved: fallback = "Your request was marked as resolved."
        }
        let text = need.ackText.trimmingCharacters(in: .whitespacesAndNewlines)
        return ("Relay", text.isEmpty ? fallback : text)
    }

    /// Posts the notification (no-op when notifications are not allowed). The identifier is per request and status, so
    /// a repeated ack replaces rather than stacks.
    static func post(need: MyNeed, status: AckStatus, text: (title: String, body: String)) {
        let content = UNMutableNotificationContent()
        content.title = text.title
        content.body = text.body
        content.sound = .default
        content.threadIdentifier = "relay-need-" + need.msgIdHex
        let request = UNNotificationRequest(identifier: "ack-\(need.msgIdHex)-\(status.code)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in }
    }
}
