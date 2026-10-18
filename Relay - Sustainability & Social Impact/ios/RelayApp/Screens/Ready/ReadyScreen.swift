import SwiftUI
import AVFoundation
import RelayCore
import RelayKit

/// Container colours of the warning level (Home banner and Ready warning card): watch is neutral, warning amber, alert red.
func levelColors(_ level: WarningLevel) -> (background: Color, foreground: Color) {
    switch level {
    case .watch: return (Color(.secondarySystemFill), .primary)
    case .warning: return (Color.orange, .black)
    case .alert: return (Color.red, .white)
    }
}

/// "Get ready": the warning, the household's plan, the nearest shelters and a checklist, all read from storage so it works in
/// airplane mode (contract v2 section 3.4). A refresh only updates the stored copy.
struct ReadyScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    @State private var player: AVAudioPlayer?
    @State private var playing = false
    @State private var playMessage: String?
    @State private var failed = false
    @State private var ticks: Set<Int> = []

    var body: some View {
        let r = strings.ready
        let snap = model.ready.snapshot
        let nowSec = Int64(Date().timeIntervalSince1970)
        let warning = ReadyView.activeWarning(snap.pack, nowSec: nowSec)
        let uiLang = model.settings.effectiveLanguage.tag
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                if let pack = snap.pack {
                    if let w = warning { warningCard(w, nowSec) } else { Text(r.noWarningNow).font(.body) }
                    if warning != nil, let plan = snap.currentPlan { planCard(plan, snap.audio, uiLang) }
                    shelterCard(pack, snap.currentPlan)
                    if !pack.checklist.isEmpty { checklistCard(pack, uiLang) }
                    footer(pack)
                } else {
                    emptyState()
                }
                if failed { Text(r.refreshFailed).font(.subheadline).foregroundStyle(.red) }
                Button(model.ready.refreshing ? r.refreshing : r.refresh) {
                    Task { failed = await model.ready.refresh(.manual).error != nil }
                }
                .buttonStyle(.relayTinted(Theme.Palette.accent))
                .disabled(model.ready.refreshing)
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.bottom, Theme.Space.xl)
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(r.readyTitle)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await model.ready.refresh(.appOpen)
            if let v = model.ready.snapshot.pack?.version { ticks = model.ready.ticks(v) }
        }
        .onDisappear { stopPlan() }
    }

    private func warningCard(_ w: ReadyWarning, _ nowSec: Int64) -> some View {
        let r = strings.ready
        let level = ReadyView.level(w)
        let colors = levelColors(level)
        let hours = ReadyView.landfallHours(w, nowSec: nowSec)
        return VStack(alignment: .leading, spacing: 6) {
            Text("\(r.levelWord(level)) · \(r.source(w.source))").font(.subheadline.weight(.semibold))
            Text(w.headline).font(.title3.bold()).accessibilityAddTraits(.isHeader)
            if w.issuedAt > 0 { Text(r.issued(w.issuedAt)).font(.subheadline) }
            if let at = w.expectedLandfallAt { Text(r.landfallAt(at)).font(.subheadline) }
            if let h = hours { Text(r.landfallIn(h)).font(.headline) }
        }
        .foregroundStyle(colors.foreground)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Theme.Space.l)
        .background(colors.background, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(r.bannerDescription(level, headline: w.headline, hours: hours))
    }

    private func planCard(_ plan: ReadyPlan, _ wav: URL?, _ uiLang: String) -> some View {
        let r = strings.ready
        return VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text(r.yourPlan).font(.headline).accessibilityAddTraits(.isHeader)
            if ReadyView.stepsInOtherLanguage(plan, uiLang: uiLang) {
                Text(r.shownInEnglish).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            }
            ForEach(Array(plan.steps.enumerated()), id: \.offset) { i, step in
                HStack(alignment: .top, spacing: Theme.Space.s) {
                    Text("\(i + 1).").font(.headline)
                    Text(step).font(.body)
                }
            }
            // One large speaker button: the stored WAV when there is one, else on-device speech in the language of the steps.
            Button { togglePlan(plan, wav) } label: {
                Label(playing ? r.stopPlan : r.hearPlan, systemImage: "speaker.wave.2.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.relayFilled(Theme.Palette.accent))
            if let m = playMessage { Text(m).font(.subheadline).foregroundStyle(.secondary) }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relaySurfaceCard()
    }

    private func togglePlan(_ plan: ReadyPlan, _ wav: URL?) {
        if playing { stopPlan(); return }
        let r = strings.ready
        if let wav, let p = try? AVAudioPlayer(contentsOf: wav) {
            player = p
            p.prepareToPlay()
            playing = p.play()
            playMessage = playing ? nil : r.noVoicePlan
            if playing {
                let length = p.duration
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: UInt64((length + 0.3) * 1_000_000_000))
                    if player === p { stopPlan() }
                }
            }
            return
        }
        let lang = Lang.fromTag(plan.stepsLang) ?? .en
        let ok = model.speakAll(plan.steps, lang: lang, onDone: { playing = false })
        playing = ok
        playMessage = ok ? nil : r.noVoicePlan
    }

    private func stopPlan() {
        player?.stop(); player = nil
        model.stopSpeaking()
        playing = false
    }

    private func shelterCard(_ pack: ReadyPack, _ plan: ReadyPlan?) -> some View {
        let r = strings.ready
        let near = plan?.nearestShelters ?? []
        return VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text(r.nearestShelters).font(.headline).accessibilityAddTraits(.isHeader)
            if near.isEmpty && pack.shelters.isEmpty { Text(r.noShelters).font(.body) }
            ForEach(Array(near.enumerated()), id: \.offset) { _, n in
                Button { nav.push(.shelter(id: n.id)) } label: {
                    HStack {
                        Text(n.name).font(.body).foregroundStyle(Color.primary)
                        Spacer()
                        Text(r.kmAway(n.distanceKm)).font(.subheadline).foregroundStyle(.secondary)
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary).accessibilityHidden(true)
                    }
                    .frame(minHeight: Theme.Size.minTouch).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if !pack.shelters.isEmpty { Button(r.allShelters) { nav.push(.shelters) } }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relaySurfaceCard()
    }

    private func checklistCard(_ pack: ReadyPack, _ uiLang: String) -> some View {
        let r = strings.ready
        return VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(r.checklist).font(.headline).accessibilityAddTraits(.isHeader)
            if ReadyView.checklistInOtherLanguage(pack, uiLang: uiLang) {
                Text(r.shownInEnglish).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            }
            ForEach(Array(pack.checklist.enumerated()), id: \.offset) { i, item in
                let done = ticks.contains(i)
                Button {
                    if done { ticks.remove(i) } else { ticks.insert(i) }
                    model.ready.setTick(pack.version, i, done: !done)
                } label: {
                    HStack(spacing: Theme.Space.m) {
                        Image(systemName: done ? "checkmark.square.fill" : "square").font(.title3)
                            .foregroundStyle(done ? Theme.Palette.accent : Color.secondary)
                        Text(item).font(.body).foregroundStyle(Color.primary).multilineTextAlignment(.leading)
                        Spacer(minLength: 0)
                    }
                    .frame(minHeight: Theme.Size.minTouch).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(done ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relaySurfaceCard()
    }

    private func footer(_ pack: ReadyPack) -> some View {
        let r = strings.ready
        return VStack(alignment: .leading, spacing: 2) {
            Text(r.updated(pack.generatedAt)).font(.footnote).foregroundStyle(.secondary)
            switch ReadyView.shelterFooter(pack) {
            case .verified: Text(r.verifiedOn(pack.sheltersPublishedAt)).font(.footnote).foregroundStyle(.secondary)
            case .demo: Text(r.demoList).font(.footnote).foregroundStyle(.secondary)
            case .none: EmptyView()
            }
        }
    }

    private func emptyState() -> some View {
        let r = strings.ready
        return VStack(alignment: .leading, spacing: Theme.Space.l) {
            VStack(alignment: .leading, spacing: Theme.Space.s) {
                Text(r.emptyTitle).font(.headline).accessibilityAddTraits(.isHeader)
                Text(r.emptyBody).font(.body)
            }
            .padding(Theme.Space.l).frame(maxWidth: .infinity, alignment: .leading).relaySurfaceCard()
            if model.ready.needsDistrict {
                VStack(alignment: .leading, spacing: Theme.Space.s) {
                    Text(r.whichDistrict).font(.headline).accessibilityAddTraits(.isHeader)
                    ForEach(AnnouncementDistricts.ids, id: \.self) { id in
                        Button(r.districtName(id)) {
                            model.ready.chooseDistrict(id)
                            Task { await model.ready.refresh(.manual) }
                        }
                        .buttonStyle(.relayTinted(Theme.Palette.accent))
                    }
                }
                .padding(Theme.Space.l).frame(maxWidth: .infinity, alignment: .leading).relaySurfaceCard()
            }
        }
    }
}
