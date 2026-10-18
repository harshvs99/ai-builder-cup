import SwiftUI

/// An inset-grouped list row: small label, value (and optional second line), trailing "Change" button.
/// Use inside a `List` / `Section` with `.listStyle(.insetGrouped)`.
struct EditableRow: View {
    var label: String
    var value: String
    var detail: String?
    var detailColor: Color
    /// Show the detail as a warning: triangle icon and amber text ("there is no GPS fix, and here is why").
    var detailIsWarning: Bool
    var warningLabel: String
    var actionTitle: String
    var action: () -> Void

    init(
        label: String,
        value: String,
        detail: String? = nil,
        detailColor: Color = .secondary,
        detailIsWarning: Bool = false,
        warningLabel: String = "",
        actionTitle: String,
        action: @escaping () -> Void
    ) {
        self.label = label
        self.value = value
        self.detail = detail
        self.detailColor = detailColor
        self.detailIsWarning = detailIsWarning
        self.warningLabel = warningLabel
        self.actionTitle = actionTitle
        self.action = action
    }

    var body: some View {
        HStack(alignment: .center, spacing: Theme.Space.m) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.footnote).foregroundStyle(.secondary)
                Text(value).font(.body)
                if let detail {
                    if detailIsWarning {
                        Label {
                            Text(detail).font(.footnote).foregroundStyle(Theme.Palette.waitingAmber)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill").font(.footnote)
                                .foregroundStyle(Theme.Palette.waitingAmber)
                                .accessibilityLabel(warningLabel)
                        }
                    } else {
                        Text(detail).font(.footnote).foregroundStyle(detailColor)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Button(actionTitle, action: action)
                .font(.body)
                .buttonStyle(.borderless)
                .frame(minHeight: Theme.Size.minTouch)
                .accessibilityLabel("\(actionTitle), \(label)")
        }
        .padding(.vertical, Theme.Space.xs)
    }
}

#Preview("Editable rows") {
    List {
        EditableRow(label: "Need", value: "Drinking water", actionTitle: "Change") {}
        EditableRow(label: "People", value: "6 · children, elderly", actionTitle: "Change") {}
        EditableRow(
            label: "Where", value: "Near the primary school", detail: "GPS · about 40 m accuracy",
            actionTitle: "Change"
        ) {}
        EditableRow(
            label: "Note", value: "Pump broken since yesterday", actionTitle: "Change"
        ) {}
    }
    .listStyle(.insetGrouped)
}
