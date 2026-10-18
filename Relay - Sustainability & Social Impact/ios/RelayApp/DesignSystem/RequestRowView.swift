import SwiftUI

enum RequestRowStatus: Sendable {
    case open, closed, cancelled
}

/// A request row (Home and Requests): category icon, three lines of text, chevron.
///   line 1: title ("Drinking water · 6 people")
///   line 2: latest step in words ("Reached a gateway phone · 2 min ago")
///   line 3 (optional): meta ("Urgent · R-7F3A · near the primary school")
/// `statusText` is the localized trailing word ("Closed", "Cancelled"); omit it while the request is open since
/// the urgency already leads the meta line.
struct RequestRowView: View {
    var category: RequestCategory
    var title: String
    var subtitle: String
    var meta: String?
    var status: RequestRowStatus
    var statusText: String?
    var urgency: UrgencyLevel?

    init(
        category: RequestCategory,
        title: String,
        subtitle: String,
        meta: String? = nil,
        status: RequestRowStatus = .open,
        statusText: String? = nil,
        urgency: UrgencyLevel? = nil
    ) {
        self.category = category
        self.title = title
        self.subtitle = subtitle
        self.meta = meta
        self.status = status
        self.statusText = statusText
        self.urgency = urgency
    }

    var body: some View {
        HStack(alignment: .center, spacing: Theme.Space.m + 2) {
            CategoryIcon(category: category, closed: status == .closed)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                if let meta {
                    Text(meta)
                        .font(.footnote)
                        .foregroundStyle(status == .open ? (urgency?.color ?? .secondary) : .secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let statusText {
                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(status == .cancelled ? Theme.Palette.cancelRed : .secondary)
            }
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
        .padding(.vertical, Theme.Space.s)
        .frame(minHeight: Theme.Size.minTouch)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, subtitle, meta, statusText].compactMap { $0 }.joined(separator: ". "))
        .accessibilityAddTraits(.isButton)
    }
}

#Preview("Request rows") {
    VStack(spacing: 0) {
        RequestRowView(
            category: .water, title: "Drinking water · 6 people",
            subtitle: "Reached a gateway phone · 2 min ago",
            meta: "Urgent · R-7F3A · near the primary school", urgency: .urgent
        )
        Divider()
        RequestRowView(
            category: .medical, title: "Medicine · 2 people", subtitle: "Delivered yesterday",
            meta: "R-21C0 · Ward 4", status: .closed, statusText: "Closed"
        )
        Divider()
        RequestRowView(
            category: .food, title: "Food · 4 people", subtitle: "Saved on this phone · just now",
            status: .cancelled, statusText: "Cancelled"
        )
    }
    .padding()
}
