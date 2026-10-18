import SwiftUI

/// Design tokens for Relay iOS. Colours come from named colours in `Assets.xcassets` (each has a dark variant);
/// values mirror `android/.../ui/theme/Color.kt`. They live in a namespace rather than as `Color` extensions so
/// they cannot collide with Xcode's generated asset symbols (`Color.surface` etc.).
enum Theme {
    enum Palette {
        /// The big mic button (Google yellow) and its glyph, both appearances.
        static let micYellow = Color("MicYellow")
        static let onMicYellow = Color("OnMicYellow")
        /// "Reachable", done timeline steps, check chips.
        static let statusGreen = Color("StatusGreen")
        static let statusGreenContainer = Color("StatusGreenContainer")
        /// Urgent fill / selected urgency segment, with `onUrgentOrange` text.
        static let urgentOrange = Color("UrgentOrange")
        static let onUrgentOrange = Color("OnUrgentOrange")
        /// Amber for "Waiting" text and dots (darker in light mode for contrast).
        static let waitingAmber = Color("WaitingAmber")
        /// Stop button, Cancel.
        static let cancelRed = Color("CancelRed")
        static let cancelRedContainer = Color("CancelRedContainer")
        /// Screen background and card surfaces.
        static let surface = Color("Surface")
        static let surfaceCard = Color("SurfaceCard")
        static let surfaceCardHigh = Color("SurfaceCardHigh")
        static let textPrimary = Color("TextPrimary")
        static let textSecondary = Color("TextSecondary")
        static let outline = Color("Outline")
        /// System blue tint (also the asset catalog's AccentColor).
        static let accent = Color.accentColor
    }

    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        /// Side gutter of tab-root screens.
        static let gutter: CGFloat = 20
    }

    enum Radius {
        static let card: CGFloat = 22
        static let tile: CGFloat = 18
        static let row: CGFloat = 12
    }

    enum Size {
        /// The home mic button (design spec: 160 pt).
        static let micButton: CGFloat = 160
        static let categoryIcon: CGFloat = 40
        static let stopButton: CGFloat = 64
        static let minTouch: CGFloat = 48
        static let timelineMarker: CGFloat = 28
    }
}

#Preview("Palette") {
    let swatches: [(String, Color)] = [
        ("micYellow", Theme.Palette.micYellow), ("statusGreen", Theme.Palette.statusGreen),
        ("urgentOrange", Theme.Palette.urgentOrange), ("waitingAmber", Theme.Palette.waitingAmber),
        ("cancelRed", Theme.Palette.cancelRed), ("surfaceCard", Theme.Palette.surfaceCard),
        ("surfaceCardHigh", Theme.Palette.surfaceCardHigh), ("outline", Theme.Palette.outline),
    ]
    return VStack(alignment: .leading, spacing: 8) {
        ForEach(swatches, id: \.0) { item in
            HStack {
                RoundedRectangle(cornerRadius: 6).fill(item.1).frame(width: 44, height: 28)
                Text(item.0).font(.footnote)
            }
        }
    }
    .padding()
    .background(Theme.Palette.surface)
}
