import SwiftUI
import RelayKit

extension EnvironmentValues {
    /// All UI text in the app language; set once at the root from `AppModel.settings.language`.
    @Entry var strings = Strings.english
}

/// Re-renders its content every 30 s with the current time in ms, so relative times ("2 min ago") stay fresh.
struct NowClock<Content: View>: View {
    var interval: TimeInterval = 30
    @ViewBuilder var content: (_ nowMs: Int64) -> Content

    var body: some View {
        TimelineView(.periodic(from: .now, by: interval)) { context in
            content(Int64(context.date.timeIntervalSince1970 * 1000))
        }
    }
}

/// "Larger text" setting: two Dynamic Type steps above whatever size the system is at.
struct LargerTextModifier: ViewModifier {
    var enabled: Bool
    @Environment(\.dynamicTypeSize) private var size

    func body(content: Content) -> some View {
        content.dynamicTypeSize(enabled ? bumped : size)
    }

    private var bumped: DynamicTypeSize {
        let all = DynamicTypeSize.allCases
        guard let i = all.firstIndex(of: size) else { return size }
        return all[min(i + 2, all.count - 1)]
    }
}
