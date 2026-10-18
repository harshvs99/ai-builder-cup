import SwiftUI

/// One node of the chain diagram.
struct ChainNode: Identifiable, Sendable {
    enum Kind: Sendable { case you, hop, gateway, unknown }
    let id = UUID()
    var kind: Kind
    var label: String
    var caption: String?
}

/// "You — 1 — Gateway · 2 hops": filled blue circle, dashed links, phone circle, green gateway circle.
/// Pass the nodes you want shown (collapse/expand per the design spec in the caller).
struct ChainDiagram: View {
    var nodes: [ChainNode]

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(nodes.enumerated()), id: \.element.id) { index, node in
                if index > 0 {
                    DashedLink()
                        .padding(.top, circleSize / 2 - 1)
                        .frame(minWidth: 16)
                }
                VStack(spacing: Theme.Space.xs) {
                    circle(for: node)
                    Text(node.label)
                        .font(.footnote.weight(.semibold))
                        .multilineTextAlignment(.center)
                    if let caption = node.caption {
                        Text(caption).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                }
                .frame(minWidth: 64)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(nodes.map { [$0.label, $0.caption].compactMap { $0 }.joined(separator: ", ") }.joined(separator: " to "))
    }

    private let circleSize: CGFloat = 44

    @ViewBuilder
    private func circle(for node: ChainNode) -> some View {
        ZStack {
            switch node.kind {
            case .you:
                Circle().fill(Theme.Palette.accent)
                Image(systemName: "person.fill").foregroundStyle(.white)
            case .hop:
                Circle().fill(Theme.Palette.surfaceCardHigh)
                Image(systemName: "iphone").foregroundStyle(Theme.Palette.textPrimary)
            case .gateway:
                Circle().fill(Theme.Palette.statusGreen)
                Image(systemName: "arrow.triangle.2.circlepath").foregroundStyle(.white)
            case .unknown:
                Circle().strokeBorder(Theme.Palette.outline, style: StrokeStyle(lineWidth: 2, dash: [4, 3]))
                Text("?").font(.headline).foregroundStyle(.secondary)
            }
        }
        .font(.body.weight(.semibold))
        .frame(width: circleSize, height: circleSize)
    }
}

private struct DashedLink: View {
    var body: some View {
        GeometryReader { geo in
            Path { p in
                p.move(to: CGPoint(x: 0, y: 1))
                p.addLine(to: CGPoint(x: geo.size.width, y: 1))
            }
            .stroke(Theme.Palette.outline, style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [4, 4]))
        }
        .frame(height: 2)
    }
}

extension ChainDiagram {
    /// Convenience for the Network card. `hops` = hops to the nearest gateway; `nil` = no gateway known;
    /// `thisPhoneIsGateway` shows only "You".
    init(
        youLabel: String, youCaption: String, hopLabel: String, gatewayLabel: String,
        hops: Int?, thisPhoneIsGateway: Bool = false
    ) {
        if thisPhoneIsGateway {
            self.init(nodes: [ChainNode(kind: .gateway, label: youLabel, caption: youCaption)])
        } else if let hops {
            var list = [ChainNode(kind: .you, label: youLabel, caption: youCaption)]
            if hops > 1 { list.append(ChainNode(kind: .hop, label: hopLabel)) }
            list.append(ChainNode(kind: .gateway, label: gatewayLabel))
            self.init(nodes: list)
        } else {
            self.init(nodes: [
                ChainNode(kind: .you, label: youLabel, caption: youCaption),
                ChainNode(kind: .unknown, label: "?"),
            ])
        }
    }
}

#Preview("Chain") {
    VStack(spacing: 32) {
        ChainDiagram(youLabel: "You", youCaption: "This phone", hopLabel: "1 hop", gatewayLabel: "Gateway · 2 hops", hops: 2)
        ChainDiagram(youLabel: "You", youCaption: "This phone", hopLabel: "1 hop", gatewayLabel: "Gateway · 1 hop", hops: 1)
        ChainDiagram(youLabel: "You", youCaption: "This phone", hopLabel: "", gatewayLabel: "", hops: nil)
    }
    .padding()
}
