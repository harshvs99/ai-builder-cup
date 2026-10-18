import SwiftUI

// All iOS 26 (Liquid Glass) availability checks live in this file. Screens call the modifiers and styles below
// and never write `#available` themselves. On iOS 17-25 they fall back to materials and flat tinted capsules.

// MARK: - Surfaces

extension View {
    /// A glass card (Liquid Glass on iOS 26, `.ultraThinMaterial` before).
    @ViewBuilder
    func relayGlassCard(cornerRadius: CGFloat = Theme.Radius.card) -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
    }

    /// A plain filled card on `surfaceCard` (list-like groups, tiles); no glass.
    func relaySurfaceCard(cornerRadius: CGFloat = Theme.Radius.card) -> some View {
        self.background(Theme.Palette.surfaceCard, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }

    /// A glass circle behind the view, optionally tinted (used for the stop button and round icon buttons).
    @ViewBuilder
    func relayGlassCircle(tint: Color? = nil, interactive: Bool = true) -> some View {
        if #available(iOS 26.0, *) {
            if let tint {
                self.glassEffect(.regular.tint(tint).interactive(interactive), in: Circle())
            } else {
                self.glassEffect(.regular.interactive(interactive), in: Circle())
            }
        } else {
            self.background(
                Circle().fill(tint ?? Color.clear)
            )
            .background(.ultraThinMaterial, in: Circle())
        }
    }

    /// Minimises the tab bar while scrolling on iOS 26; no-op before.
    @ViewBuilder
    func relayTabBarMinimizeOnScroll() -> some View {
        if #available(iOS 26.0, *) {
            self.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }
}

/// Groups nearby glass elements so they blend (iOS 26); a plain container before.
struct RelayGlassContainer<Content: View>: View {
    var spacing: CGFloat = Theme.Space.m
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content() }
        } else {
            content()
        }
    }
}

// MARK: - Capsule buttons

/// The flavours of capsule button in the design.
enum RelayCapsuleKind {
    /// Tinted glass: tinted text on a soft tint ("Type instead", "+ Add", "× Cancel").
    case tinted(Color)
    /// Prominent: solid tint fill with contrasting text ("Send", "Done, check it").
    case filled(Color, foreground: Color = .white)
    /// Neutral grey glass ("Change something", "‹ Back").
    case grey
}

struct RelayCapsuleButtonStyle: ButtonStyle {
    var kind: RelayCapsuleKind
    var fullWidth = false

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(foreground)
            .multilineTextAlignment(.center)
            .padding(.horizontal, Theme.Space.xl)
            .padding(.vertical, Theme.Space.m)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: Theme.Size.minTouch + 8)
            .modifier(CapsuleBackground(kind: kind))
            .contentShape(Capsule())
            .opacity(isEnabled ? (configuration.isPressed ? 0.75 : 1) : 0.45)
    }

    private var foreground: Color {
        switch kind {
        case .tinted(let tint): return tint
        case .filled(_, let fg): return fg
        case .grey: return Theme.Palette.textPrimary
        }
    }
}

private struct CapsuleBackground: ViewModifier {
    var kind: RelayCapsuleKind

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            switch kind {
            case .filled(let tint, _):
                content.glassEffect(.regular.tint(tint).interactive(), in: Capsule())
            case .tinted(let tint):
                content.glassEffect(.regular.tint(tint.opacity(0.28)).interactive(), in: Capsule())
            case .grey:
                content.glassEffect(.regular.interactive(), in: Capsule())
            }
        } else {
            switch kind {
            case .filled(let tint, _):
                content.background(tint, in: Capsule())
            case .tinted(let tint):
                content
                    .background(tint.opacity(0.16), in: Capsule())
                    .background(.ultraThinMaterial, in: Capsule())
            case .grey:
                content
                    .background(Theme.Palette.surfaceCardHigh, in: Capsule())
            }
        }
    }
}

extension ButtonStyle where Self == RelayCapsuleButtonStyle {
    /// Tinted glass capsule, e.g. `.buttonStyle(.relayTinted())`.
    static func relayTinted(_ tint: Color = Theme.Palette.accent, fullWidth: Bool = false) -> RelayCapsuleButtonStyle {
        RelayCapsuleButtonStyle(kind: .tinted(tint), fullWidth: fullWidth)
    }
    /// Filled prominent capsule (blue by default; pass `Theme.Palette.cancelRed` etc.).
    static func relayFilled(
        _ tint: Color = Theme.Palette.accent, foreground: Color = .white, fullWidth: Bool = true
    ) -> RelayCapsuleButtonStyle {
        RelayCapsuleButtonStyle(kind: .filled(tint, foreground: foreground), fullWidth: fullWidth)
    }
    /// Grey glass capsule ("Change something").
    static func relayGrey(fullWidth: Bool = true) -> RelayCapsuleButtonStyle {
        RelayCapsuleButtonStyle(kind: .grey, fullWidth: fullWidth)
    }
}

// MARK: - Round stop button

/// 64 pt circular red glass button with a stop glyph ("Stop listening").
struct StopCircleButton: View {
    var accessibilityLabel: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "stop.fill")
                .font(.title2.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: Theme.Size.stopButton, height: Theme.Size.stopButton)
                .relayGlassCircle(tint: Theme.Palette.cancelRed)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
    }
}

#Preview("Glass buttons") {
    ZStack {
        LinearGradient(colors: [.blue.opacity(0.25), .teal.opacity(0.2)], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        VStack(spacing: 16) {
            Text("Glass card").padding().frame(maxWidth: .infinity).relayGlassCard()
            Button("Type instead") {}.buttonStyle(.relayTinted())
            Button("Done, check it") {}.buttonStyle(.relayFilled())
            Button("Change something") {}.buttonStyle(.relayGrey())
            Button("Disabled") {}.buttonStyle(.relayFilled()).disabled(true)
            StopCircleButton(accessibilityLabel: "Stop listening") {}
        }
        .padding()
    }
}
