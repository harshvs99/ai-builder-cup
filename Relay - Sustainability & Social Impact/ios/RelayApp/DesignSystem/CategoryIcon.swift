import SwiftUI

/// Request categories as the design shows them. Local to the design system so views stay free of RelayCore;
/// map from `RelayCore.Category` at the call site.
enum RequestCategory: String, CaseIterable, Sendable {
    case medical, rescue, water, food, shelter, missingPerson, other

    /// SF Symbol for the category.
    var systemImage: String {
        switch self {
        case .medical: return "cross.case.fill"
        case .rescue: return "lifepreserver.fill"
        case .water: return "drop.fill"
        case .food: return "fork.knife"
        case .shelter: return "house.fill"
        case .missingPerson: return "person.fill.questionmark"
        case .other: return "questionmark"
        }
    }

    var tint: Color {
        switch self {
        case .medical: return .red
        case .rescue: return .orange
        case .water: return .blue
        case .food: return .brown
        case .shelter: return .teal
        case .missingPerson: return .purple
        case .other: return .gray
        }
    }
}

/// Leading circle of a request row: category symbol on a soft tint, or a green check circle when `closed`.
/// Decorative; the row's text carries the meaning.
struct CategoryIcon: View {
    var category: RequestCategory
    var closed = false
    var size: CGFloat = Theme.Size.categoryIcon

    var body: some View {
        ZStack {
            if closed {
                Circle().fill(Theme.Palette.statusGreen)
                Image(systemName: "checkmark").foregroundStyle(.white)
            } else {
                Circle().fill(category.tint.opacity(0.18))
                Image(systemName: category.systemImage).foregroundStyle(category.tint)
            }
        }
        .font(.system(size: size * 0.42, weight: .semibold))
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

#Preview("Category icons") {
    HStack(spacing: 12) {
        ForEach(RequestCategory.allCases, id: \.self) { CategoryIcon(category: $0) }
        CategoryIcon(category: .water, closed: true)
    }
    .padding()
}
